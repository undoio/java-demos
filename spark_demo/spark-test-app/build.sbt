name := "spark-test-app"
version := "0.1.0"
scalaVersion := "2.12.15"

// Set Spark dependencies as "provided" since they'll be available in the Spark cluster
libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.4.0" % "provided",
  "org.apache.spark" %% "spark-sql" % "3.4.0" % "provided",

  // AWS SDK for S3 operations - use consistent versions
  "software.amazon.awssdk" % "s3" % "2.21.0",
  "software.amazon.awssdk" % "auth" % "2.21.0",
  "software.amazon.awssdk" % "aws-core" % "2.21.0",
  "software.amazon.awssdk" % "regions" % "2.21.0",
  "software.amazon.awssdk" % "sdk-core" % "2.21.0",
  "software.amazon.awssdk" % "utils" % "2.21.0",
  "software.amazon.awssdk" % "http-client-spi" % "2.21.0",
  "software.amazon.awssdk" % "apache-client" % "2.21.0",
  // HTTP implementation for AWS SDK - just using URL connection client
  // "software.amazon.awssdk" % "url-connection-client" % "2.21.0",
  // Use Apache HTTP client instead of URL connection client
  "software.amazon.awssdk" % "apache-client" % "2.21.0",

  // Add SLF4J implementation to avoid warnings
  "org.slf4j" % "slf4j-simple" % "2.0.7"
)

// Don't include Scala library in the assembly JAR since Spark provides it
ThisBuild / assemblyPackageScala / assembleArtifact := false

// Assembly settings for merging dependencies
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  // Required to resolve AWS SDK conflicts
  case "module-info.class" => MergeStrategy.discard
  case "mime.types" => MergeStrategy.first
  case "mozilla/public-suffix-list.txt" => MergeStrategy.first
  case "codegen-resources/service-2.json" => MergeStrategy.first
  case "codegen-resources/customization.config" => MergeStrategy.first
  case "codegen-resources/paginators-1.json" => MergeStrategy.first
  case "codegen-resources/waiters-2.json" => MergeStrategy.first
  case "META-INF/io.netty.versions.properties" => MergeStrategy.first
  case x => MergeStrategy.first
}
