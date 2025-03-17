import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets

object TestSparkApp {
    def main(args: Array[String]): Unit = {
        val spark = SparkSession
            .builder()
            .appName("Sales Analysis")
            // .master("local[*]")  // Forces Spark to run locally
            .getOrCreate()
        import spark.implicits._

        println("Starting sales analysis application")

        // Get and save the executor count
        val sc = spark.sparkContext
        val executorMemoryStatus = sc.getExecutorMemoryStatus
        // This map includes "driver" plus all executor IDs
        val numExecutors = executorMemoryStatus.size - 1
        println(s"Number of executors: $numExecutors")

        // Write this information to a file in the shared volume
        Files.write(
          Paths.get("/recordings/executor-count.txt"),
          numExecutors.toString.getBytes(StandardCharsets.UTF_8)
        )

        // Generate sample sales data
        // Each sale has: date, product_id, quantity, unit_price
        val numRecords = 10000
        val startDate = "2025-01-01"

        var lastMonthRevenue = 0.0  // Track previous month's revenue

        val salesData = spark.range(0, numRecords).map { i =>
            if (i == 23) {
                println("running on executor with i = " + i);
            }
            val dayOffset = i % 365  // Spread across a year
            val date = java.sql.Date.valueOf(startDate).toLocalDate.plusDays(dayOffset)
            val productId = i % 100  // 100 different products
            // Introduce subtle bug: quantities occasionally become negative
            // due to integer overflow in a seemingly innocent calculation
            val quantity = {
                val baseQuantity = (math.random * 10).toInt + 1
                if (dayOffset > 180) {  // Only overflow in second half of year
                    // This looks innocent but can cause integer overflow
                    baseQuantity * (Integer.MAX_VALUE / baseQuantity + 1)
                } else baseQuantity
            }
            val unitPrice = (math.random * 100).toInt + 1  // $1-100
            (date, productId, quantity, unitPrice)
        }.toDF("date", "product_id", "quantity", "unit_price")

        // Calculate revenue per sale
        val salesWithRevenue = salesData
            .withColumn("revenue", col("quantity").cast("double") * col("unit_price"))

        // Monthly aggregations
        val monthlyStats = salesWithRevenue
            .withColumn("month", date_format(col("date"), "yyyy-MM"))
            .groupBy("month")
            .agg(
                sum("revenue").cast("double").as("total_revenue"),  // Explicitly cast to double
                avg("revenue").as("avg_revenue_per_sale"),
                count("*").as("num_sales"),
                sum("quantity").as("total_items_sold")
            )
            .orderBy("month")

        // Product performance analysis
        val productStats = salesWithRevenue
            .groupBy("product_id")
            .agg(
                sum("revenue").as("total_revenue"),
                sum("quantity").as("total_quantity"),
                avg("unit_price").as("avg_price")
            )
            .orderBy(desc("total_revenue"))
            .limit(10)
        // Process monthly stats and check for anomalies
        monthlyStats.collect().foreach { row =>
            val currentRevenue = row.getAs[Double]("total_revenue")

            // Perfect breakpoint location: This check will only trigger
            // when we detect a suspicious revenue change
            if (lastMonthRevenue > 0 &&
                math.abs(currentRevenue - lastMonthRevenue) / lastMonthRevenue > 2.0) {
                // Revenue changed by more than 200% - highly suspicious!
                println(s"ANOMALY DETECTED: Suspicious revenue change detected!")
                println(s"Previous month revenue: $lastMonthRevenue")
                println(s"Current month revenue: $currentRevenue")
                assert(false, "Revenue change exceeds threshold") // Great breakpoint location
            }

            lastMonthRevenue = currentRevenue
        }

        // Display results
        println("\nMonthly Sales Statistics:")
        monthlyStats.show()

        // Add some diagnostic information
        println("\nChecking for unusual quantities:")
        salesWithRevenue
            .select("quantity", "revenue")
            .orderBy(desc("quantity"))
            .limit(5)
            .show()

        println("\nTop 10 Products by Revenue:")
        productStats.show()

        // Calculate some overall statistics
        val overallStats = salesWithRevenue.agg(
            sum("revenue").as("total_revenue"),
            avg("revenue").as("avg_revenue_per_sale"),
            count("*").as("total_sales"),
            countDistinct("product_id").as("unique_products")
        )

        println("\nOverall Statistics:")
        overallStats.show()

        // Introduce a fatal error
        println("About to introduce fatal error...")
        salesWithRevenue.foreach(row => {
            if (row.getAs[Long]("revenue") > 0) {
                throw new RuntimeException("Simulated fatal error during processing")
            }
        })

        // We'll never get here

        // Sleep briefly to allow time to see the results
        println("Analysis complete, sleeping for 5 seconds...")
        Thread.sleep(5000)

        println("Application completing...")
        spark.stop()
    }
}
