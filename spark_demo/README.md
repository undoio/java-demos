# Spark Recording Collection with Undo

This project demonstrates how to collect execution recordings from a Spark application running in Kubernetes using Undo's LiveRecorder, and automatically upload them to Amazon S3.

## Overview

The system uses JVMTI agent integration to capture execution recordings from both the Spark driver and executor pods. It implements a coordinated approach where:

1. The Spark driver keeps track of how many executors are running
2. Each executor creates a recording when it completes
3. The driver waits for all executor recordings before uploading all recordings to S3

## Architecture

### Components

- **JVMTI Agent**: The `lr4j_agent_x64.so` native agent is attached to JVMs to generate recordings
- **ExitHandler**: A Java class that manages recordings and uploads them to S3
- **K8s Persistent Volume**: Shared storage for all recordings
- **AWS S3**: Final storage location for all collected recordings

### Key Implementation Details

- `terminationGracePeriodSeconds` is set to a high value (240 seconds) to ensure executors have enough time to save their recordings before being terminated
- File-based coordination between executors and driver via shared PVC
- Recording files are named with pod name and timestamp for easy identification
- Driver waits for all executor recordings before uploading everything to S3

## Project Structure

```
/
├── deploy.sh                # Deployment script
├── Dockerfile.spark         # Spark container image with Undo agent
├── spark-test-app/
│   ├── src/main/java/
│   │   └── ExitHandler.java # Recording handler for coordination and S3 upload
│   ├── src/main/scala/
│   │   └── TestSparkApp.scala # Sample Spark application
│   ├── pv.yaml              # Persistent Volume configuration
│   ├── pvc.yaml             # Persistent Volume Claim configuration
│   └── spark-test-app.yaml  # Spark application K8s configuration
└── build.sbt                # Build configuration with AWS SDK dependencies
```

> [!NOTE]
> You need to change `/home/ubuntu` to match your own directory structure.

## How It Works

### Host Directory Sharing

A key aspect of this implementation is making recordings accessible outside of Kubernetes/Docker:

1. A host directory (`/home/ubuntu/spark-recordings`) is created on the physical machine
2. This directory is:
   - Made accessible with wide permissions (`chmod 777`)
   - Mounted into Minikube using `minikube mount`
   - Referenced in the Kubernetes PV configuration
3. This allows recordings to be:
   - Accessed directly from the host machine for debugging
   - Persisted even if the Kubernetes cluster is destroyed
   - Shared between different Kubernetes pods
   - Available to the S3 upload process

The mount is set up in the `deploy.sh` script:
```bash
mkdir -p /home/ubuntu/spark-recordings
sudo chmod 777 /home/ubuntu/spark-recordings
minikube mount /home/ubuntu/spark-recordings:/home/ubuntu/spark-recordings &
```

And referenced in the PV configuration:
```yaml
# In pv.yaml
spec:
  hostPath:
    path: /home/ubuntu/spark-recordings  # Mount this host directory
```

### Recording Coordination Process

1. The Spark application starts and writes the actual executor count to a file
2. When each executor completes its work:
   - The JVMTI agent calls `ExitHandler.recordingSaved()`
   - The recording is copied to the shared volume
   - A "completion marker" file is created
3. When the driver completes:
   - It calls the same `ExitHandler.recordingSaved()`
   - It reads the executor count file
   - It waits for markers from all executors (with timeout)
   - It uploads all recordings to S3 using the AWS SDK

### Critical Configuration

1. **PVC Mount**: Both driver and executors mount the same volume at `/recordings`

2. **JVMTI Agent Configuration**:
   ```
   -agentpath:/opt/agent/lr4j_agent_x64.so=save_on=always,save_callback_class=ExitHandler,save_callback_jar=/opt/spark/examples/jars/spark-test-app-assembly-0.1.0.jar
   ```

3. **Termination Grace Period**:
   ```yaml
   terminationGracePeriodSeconds: 240  # Important: Allows time for recordings to be saved
   ```

4. **Region Configuration**: Ensure AWS region is consistent across all components:
   ```
   AWS_REGION=eu-west-2
   ```

## Deployment

1. Configure AWS credentials:
   ```bash
   kubectl create secret generic aws-credentials \
       --from-literal=aws-access-key-id=YOUR_ACCESS_KEY \
       --from-literal=aws-secret-access-key=YOUR_SECRET_KEY
   ```

2. Deploy the application:
   ```bash
   ./deploy.sh setup
   ```

3. Check logs to verify recordings were saved:
   ```bash
   kubectl logs -f spark-test-app-driver
   ```

## Common Issues

- **Termination Grace Period Too Short**: If executors are terminated before copying recordings, increase `terminationGracePeriodSeconds`
- **Region Mismatch**: Ensure AWS_REGION is set consistently across all configurations
- **S3 Upload Failures**: Check AWS credentials and bucket permissions

## Requirements

- Kubernetes cluster with Spark Operator
- Minikube for local testing
- AWS account with S3 bucket
- Undo LiveRecorder agent for Java
