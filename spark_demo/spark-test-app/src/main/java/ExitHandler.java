import java.net.InetAddress;
import java.net.UnknownHostException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.nio.file.*;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import java.net.URI;

// AWS SDK imports
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
// import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

public class ExitHandler {
    // S3 bucket name - replace with your actual bucket name
    private static final String S3_BUCKET_NAME = "david-undo-test-bucket";

    static {
        System.out.println("ExitHandler class being loaded");
    }

    public static void recordingSaved(String filename) {
        System.out.println("recordingSaved: " + filename);

        // Get pod name and role (driver or executor)
        String podName = System.getenv("HOSTNAME");
        boolean isDriver = podName.contains("driver");

        // Check if AWS credentials are set
        String awsAccessKeyId = System.getenv("AWS_ACCESS_KEY_ID");
        String awsSecretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        boolean hasAwsCredentials = awsAccessKeyId != null && !awsAccessKeyId.isEmpty() &&
                                    awsSecretKey != null && !awsSecretKey.isEmpty();

        if (isDriver) {
            // Copy recording to shared PVC
            String pvcFilePath = copyToPVC(filename);

            // Driver: wait for executor recordings and upload all files
            System.out.println("Driver recording saved. Waiting for executors to start...");
            waitForExecutorRecordings("starting");
            System.out.println("Driver recording saved. Waiting for executor recordings...");
            waitForExecutorRecordings("complete");

            if (hasAwsCredentials) {
                System.out.println("AWS credentials found. Uploading all recordings to S3...");
                uploadAllRecordingsToS3();
            } else {
                System.out.println("AWS credentials not found. Skipping S3 upload.");
            }
        } else {
            // Simulate what would happen if the executors were much slower at creating
            // their recordings than the driver. This proves that the executors don't get
            // killed until their terminationGracePeriodSeconds expires
            try {
                Thread.sleep(15 * 1000);
            } catch (InterruptedException e) {}
            System.out.println("Notify that we are starting our copy");
            // Copy recording to shared PVC
            String pvcFilePath = copyToPVC(filename);
            createCompletionMarker(podName, "starting");
            try {
                Thread.sleep(1 * 60 * 1000);
            } catch (InterruptedException e) {}
            // Executor: create a marker file to signal completion
            System.out.println("Executor recording saved. Creating completion marker.");
            createCompletionMarker(podName, "complete");
        }

        // onExit();
    }

    private static String copyToPVC(String filename) {
        String pvcDir = "/recordings/"; // PVC mount directory
        Path sourcePath = Paths.get(filename);
        String podName = System.getenv("HOSTNAME"); // Get pod name
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date());
        String newFilename = podName + "_" + timestamp + ".undo";
        Path destPath = Paths.get(pvcDir, newFilename);

        try {
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Successfully copied recording to PVC: " + destPath);
            return destPath.toString();
        } catch (IOException e) {
            System.err.println("Error copying recording to PVC: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static void createCompletionMarker(String podName, String marker) {
        try {
            Path markerPath = Paths.get("/recordings", podName + "." + marker);
            Files.write(markerPath, podName.getBytes());
            System.out.println("Created completion marker: " + markerPath);
        } catch (IOException e) {
            System.err.println("Error creating completion marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int getExpectedExecutorCount() {
        try {
            Path countFile = Paths.get("/recordings/executor-count.txt");
            if (Files.exists(countFile)) {
                String countStr = new String(Files.readAllBytes(countFile));
                return Integer.parseInt(countStr.trim());
            }
        } catch (Exception e) {
            System.err.println("Error reading executor count: " + e.getMessage());
        }
        // Default if file doesn't exist or can't be read
        return 2;
    }

    private static void waitForExecutorRecordings(String marker) {
        try {
            // Get number of executors from file
            int expectedExecutors = getExpectedExecutorCount();

            System.out.println("Driver waiting for " + expectedExecutors + " executor recordings");

            // Poll the directory until all completion markers are found
            long startTime = System.currentTimeMillis();
            long timeout = 5 * 60 * 1000; // 5 minutes timeout

            while (true) {
                // Count completion marker files
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("/recordings"), "*." + marker)) {
                    long count = StreamSupport.stream(stream.spliterator(), false).count();
                    System.out.println("Found " + count + " of " + expectedExecutors + " completion markers");

                    if (count >= expectedExecutors) {
                        System.out.println("All executor recordings received!");
                        break;
                    }
                }

                // Check timeout
                if (System.currentTimeMillis() - startTime > timeout) {
                    System.err.println("Timeout waiting for executor recordings");
                    break;
                }

                // Wait before checking again
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            System.err.println("Error waiting for executor recordings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void uploadAllRecordingsToS3() {
        try {
            System.out.println("Uploading all recordings to S3");

            // Find all recording files
            List<Path> recordingFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("/recordings"), "*.undo")) {
                stream.forEach(recordingFiles::add);
            }

            System.out.println("Found " + recordingFiles.size() + " recording files to upload");

            // Create S3 client
            S3Client s3Client = createS3Client();

            // Upload each file to S3
            for (Path recordingPath : recordingFiles) {
                uploadFileToS3(s3Client, recordingPath.toString());
            }

            System.out.println("All recordings uploaded to S3");
        } catch (IOException e) {
            System.err.println("Error uploading recordings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static S3Client createS3Client() {
        // Get region from environment variable or use default
        String regionName = System.getenv("AWS_REGION");
        if (regionName == null || regionName.isEmpty()) {
            regionName = "eu-west-2"; // Default region
        }

        Region region = Region.of(regionName);

        // Create S3 client using environment credentials
        return S3Client.builder()
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(region)
            .httpClient(ApacheHttpClient.builder().build())
            // Set S3 to use path-style access instead of virtual-hosted style
            // .serviceConfiguration(s -> s.pathStyleAccessEnabled(true))
            // Set the exact endpoint that worked with the CLI
            // .endpointOverride(URI.create("https://s3.eu-west-2.amazonaws.com"))
            .build();
    }

    private static void uploadFileToS3(S3Client s3Client, String filePath) {
        try {
            Path path = Paths.get(filePath);
            String key = path.getFileName().toString(); // Use the file name as the S3 key

            // Create a PutObjectRequest
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(S3_BUCKET_NAME)
                .key(key)
                .build();

            // Upload file
            s3Client.putObject(request, path);
            System.out.println("Successfully uploaded " + key + " to S3 bucket " + S3_BUCKET_NAME);
        } catch (Exception e) {
            System.err.println("Error uploading " + filePath + " to S3: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void onExit() {
        System.out.println("ExitHandler.onExit() called - starting upload simulation");
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String podName = System.getenv("HOSTNAME");
            System.out.println("Running in Kubernetes host: " + hostname + " and pod: " + podName);

            int waitTime = 60;
            if (hostname.equals("spark-test-app-driver")) {
                waitTime = 90;
            }

            // Simulate some work on exit
            for (int i = 1; i < waitTime; i++) {
                System.out.println("Exit handler working: " + i);
                Thread.sleep(1000);
            }
        } catch (UnknownHostException e) {
            System.err.println("Could not get hostname: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Exit handler interrupted: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error in exit handler: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
