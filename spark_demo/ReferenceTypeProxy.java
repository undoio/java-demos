import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concise JDWP proxy that
 *  • Optionally caches replies to all ReferenceType (cmd‑set = 2) commands keyed by (cmd, referenceTypeId)
 *  • Flushes the cache on any command whose command‑set ≠ 2
 *  • Reports statistics on disconnect:
 *      – cache hits / misses
 *      – idle time between IDE ReferenceType commands (total, average)
 *      – VM processing time per forwarded ReferenceType (total, average, max)
 *
 * Usage:
 *   java ReferenceTypeProxy <listen‑port> <target‑host> <target‑port> [--nocache]
 */
public class ReferenceTypeProxy {

    /* ====== JDWP framing constants ====== */
    private static final int  HDR         = 11;
    private static final byte FLAG_REPLY  = (byte) 0x80;
    private static final byte SET_REFTYPE = 2;

    /* ====== key for the cache ====== */
    private record Key(byte cmd, long refId) { }

    /* ====== per‑connection state ====== */
    private final boolean populateCache;
    private final Map<Key, byte[]>  cache   = new ConcurrentHashMap<>();
    private final Map<Integer, Key> waiting = new ConcurrentHashMap<>();

    /* stats: cache + IDE idle */
    private final AtomicLong hits  = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong waitTotalNanos = new AtomicLong();
    private final AtomicLong waitCount      = new AtomicLong();

    /* stats: VM processing time */
    private final AtomicLong vmTotalNanos = new AtomicLong();
    private final AtomicLong vmCount      = new AtomicLong();
    private final AtomicLong vmMaxNanos   = new AtomicLong();
    private final Map<Integer, Long> pendingTimes = new ConcurrentHashMap<>();

    /* IDE‑side timing helpers */
    private volatile boolean runActive = false;
    private volatile long    lastCmdTime = 0;

    private ReferenceTypeProxy(boolean populateCache) {
        this.populateCache = populateCache;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: java ReferenceTypeProxy <listen-port> <target-host> <target-port> [--nocache]");
            System.exit(1);
        }

        int    listenPort = Integer.parseInt(args[0]);
        String host       = args[1];
        int    targetPort = Integer.parseInt(args[2]);
        boolean populate  = !(args.length == 4 && "--nocache".equalsIgnoreCase(args[3]));

        try (ServerSocket ss = new ServerSocket(listenPort)) {
            while (true) {
                Socket ide = ss.accept();
                Socket vm  = new Socket(host, targetPort);
                ide.setTcpNoDelay(true);
                vm.setTcpNoDelay(true);
                handshake(ide, vm);
                var proxy = new ReferenceTypeProxy(populate);
                new Thread(() -> proxy.pipe(ide, vm, true )).start();  // IDE → VM
                new Thread(() -> proxy.pipe(vm , ide, false)).start(); // VM  → IDE
            }
        }
    }

    /* ================= pipe one direction ================= */

    private void pipe(Socket srcSock, Socket dstSock, boolean ideToVm) {
        try (InputStream  in  = srcSock.getInputStream();
             OutputStream dst = dstSock.getOutputStream();
             OutputStream src = srcSock.getOutputStream()) {

            byte[] hdr = new byte[HDR];

            while (readFully(in, hdr, HDR)) {
                ByteBuffer h = ByteBuffer.wrap(hdr);
                int   len      = h.getInt();
                int   id       = h.getInt();
                byte  flag     = h.get();
                short cmdOrErr = h.getShort();        // cmd-set+cmd OR error-code
                byte  set      = (byte) (cmdOrErr >> 8);
                byte  cmd      = (byte) cmdOrErr;

                byte[] body = new byte[len - HDR];
                readFully(in, body, body.length);

                boolean isCommand = (flag & FLAG_REPLY) == 0;

                if (isCommand) {                       /* ---------- command ---------- */
                    if (ideToVm) trackIdle(set);       // timings only on IDE side

                    if (set == SET_REFTYPE) {
                        long refId = ByteBuffer.wrap(body).getLong();
                        Key key = new Key(cmd, refId);

                        if (populateCache) {
                            byte[] cached = cache.get(key);
                            if (cached != null) {       // serve from cache
                                hits.incrementAndGet();
                                sendReply(src, id, cached);
                                continue;               // skip VM
                            }
                        }

                        // Going to VM ⇒ track miss & timing
                        misses.incrementAndGet();
                        pendingTimes.put(id, System.nanoTime());
                        if (populateCache) waiting.put(id, key);
                    } else {
                        flush();                        // different command‑set ⇒ drop cache & run
                        if (ideToVm) endRun();
                    }
                } else {                                /* ---------- reply ------------ */
                    // VM processing time
                    Long start = pendingTimes.remove(id);
                    if (start != null) {
                        long dur = System.nanoTime() - start;
                        vmTotalNanos.addAndGet(dur);
                        vmCount.incrementAndGet();
                        vmMaxNanos.updateAndGet(prev -> Math.max(prev, dur));
                    }

                    if (populateCache) {
                        Key key = waiting.remove(id);
                        if (key != null && cmdOrErr == 0)
                            cache.put(key, body.clone());
                    }
                }

                // forward frame unchanged
                dst.write(hdr);
                dst.write(body);
                dst.flush();
            }
        } catch (IOException ignored) { }
        finally {
            if (ideToVm) {
                endRun();
                reportStats();
            }
            try { dstSock.close(); } catch (IOException ignored) {}
            try { srcSock.close(); } catch (IOException ignored) {}
        }
    }

    /* ============ timing helpers (IDE side only) ============ */

    private void trackIdle(byte cmdSet) {
        long now = System.nanoTime();
        if (cmdSet == SET_REFTYPE) {
            if (runActive) {
                waitTotalNanos.addAndGet(now - lastCmdTime);
                waitCount.incrementAndGet();
            } else {
                runActive = true; // start of a run
            }
            lastCmdTime = now;
        }
    }

    private void endRun() {
        runActive = false;
        lastCmdTime = 0;
    }

    /* ============ misc helpers ============ */

    private void flush() {
        cache.clear();
        waiting.clear();
    }

    private static void sendReply(OutputStream out, int id, byte[] body) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HDR + body.length);
        buf.putInt(HDR + body.length)
           .putInt(id)
           .put(FLAG_REPLY)
           .putShort((short) 0)
           .put(body);
        out.write(buf.array());
        out.flush();
    }

    /** Perform the JDWP handshake. */
    private static void handshake(Socket ide, Socket vm) throws IOException {
        byte[] buf = new byte[14];
        // IDE → VM
        readFully(ide.getInputStream(), buf, buf.length);
        vm.getOutputStream().write(buf); vm.getOutputStream().flush();
        // VM → IDE
        readFully(vm.getInputStream(), buf, buf.length);
        ide.getOutputStream().write(buf); ide.getOutputStream().flush();
    }

    /** Read exactly {@code len} bytes into {@code dest}. Returns false on EOF. */
    private static boolean readFully(InputStream in, byte[] dest, int len) throws IOException {
        for (int n = 0, r; n < len; n += r)
            if ((r = in.read(dest, n, len - n)) < 0) return false;
        return true;
    }

    /* ============ reporting ============ */

    private void reportStats() {
        double idleTotMs = waitTotalNanos.get() / 1_000_000.0;
        long idleCnt = waitCount.get();
        double idleAvgMs = idleCnt == 0 ? 0.0 : idleTotMs / idleCnt;

        double vmTotMs = vmTotalNanos.get() / 1_000_000.0;
        long vmCnt = vmCount.get();
        double vmAvgMs = vmCnt == 0 ? 0.0 : vmTotMs / vmCnt;
        double vmMaxMs = vmMaxNanos.get() / 1_000_000.0;

        System.err.printf("ReferenceTypeProxy stats — hits:%d, misses:%d, wait total:%.3f ms, avg:%.3f ms%n",
                          hits.get(), misses.get(), idleTotMs, idleAvgMs);
    }
}
