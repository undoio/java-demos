#!/bin/bash
set -e

# Usage message
usage() {
    echo "Usage: $0 {setup|redeploy|clean}"
    echo "  setup   - Create project structure, build and push all images"
    echo "  redeploy- Update the application JAR in the Spark image and reapply Kubernetes config"
    echo "  clean   - Remove dangling Docker images/containers"
    exit 1
}

# Check for command argument
if [ -z "$1" ]; then
    usage
fi

COMMAND="$1"

# Setup recordings directory permissions in minikube
setup_recordings_dir() {
    echo "Setting up recordings directory..."

    # Create directory on host if it doesn't exist
    mkdir -p /home/ubuntu/spark-recordings
    sudo chmod 777 /home/ubuntu/spark-recordings

    # Kill any existing mount process
    if [ -f .minikube-mount.pid ]; then
        kill $(cat .minikube-mount.pid) || true
        rm .minikube-mount.pid
    fi

    # Start mount in background and save PID
    minikube mount /home/ubuntu/spark-recordings:/home/ubuntu/spark-recordings &
    echo $! > .minikube-mount.pid

    # Give mount a moment to establish
    sleep 2
}

# Ensure we're using minikube's docker daemon
setup_docker_env() {
    # Check if minikube is running
    if ! minikube status | grep -q "Running"; then
        echo "Minikube not running, starting it..."
        minikube start --memory=8192 --cpus=4 --driver=docker \
            --mount-string="/home/ubuntu/spark-recordings:/home/ubuntu/spark-recordings" \
            --mount --mount-permissions="0777"
    fi

    # Check if registry addon is enabled
    if ! minikube addons list | grep "registry" | grep -q "enabled"; then
        echo "Registry addon not enabled, enabling it..."
        minikube addons enable registry
    fi

    echo "Configuring Docker to use minikube's daemon..."
    eval "$(minikube docker-env)"
}

# Copy the precompiled agent
copy_agent() {
    echo "Copying precompiled agent..."
    cp lr4j_agent_x64.so spark/
}

# Build the Spark docker image
build_spark_image() {
    echo "Building spark-custom image..."
    docker build -t localhost:5000/spark-custom:latest spark
}

# Build the application with SBT and update the spark image
update_spark_custom_with_app() {
    echo "Building spark-test-app with sbt..."
    pushd spark-test-app > /dev/null
    sbt clean assembly
    popd > /dev/null

    echo "Injecting spark-test-app jar into spark-custom image..."
    # Create a temporary container from the spark-custom image
    container_id=$(docker create localhost:5000/spark-custom:latest)
    # Copy the assembled jar into the container
    docker cp spark-test-app/target/scala-2.12/spark-test-app-assembly-0.1.0.jar "${container_id}":/opt/spark/examples/jars/
    # Commit the container as the new spark-custom image
    docker commit "${container_id}" localhost:5000/spark-custom:latest
    docker rm "${container_id}"
}

# Push images to the local registry
push_images() {
    echo "Pushing images to local registry..."
    docker push localhost:5000/spark-custom:latest
}

# Clean up dangling images and containers
cleanup_docker() {
    echo "Cleaning up dangling Docker images and containers..."
    docker image prune -f
    docker container prune -f
}

# Deploy (or re-deploy) to Kubernetes
deploy_k8s() {
    echo "Deploying to Kubernetes..."

    # Clean up existing resources
    kubectl delete -f spark-test-app/spark-test-app.yaml --ignore-not-found
    kubectl delete pvc spark-recording-pvc --ignore-not-found
    kubectl delete pv spark-hostpath-pv --ignore-not-found

    # Setup directory permissions
    setup_recordings_dir

    # Apply the new resources
    echo "Applying PersistentVolume and PersistentVolumeClaim..."
    kubectl apply -f spark-test-app/pv.yaml
    kubectl apply -f spark-test-app/pvc.yaml

    echo "Applying Spark application..."
    kubectl apply -f spark-test-app/spark-test-app.yaml
}

# Setup project structure and copy necessary files
setup_project_structure() {
    echo "Creating project structure..."
    mkdir -p spark spark/build
    cp Dockerfile.spark spark/Dockerfile
}

# Main logic
case "$COMMAND" in
    setup)
        setup_project_structure
        setup_docker_env
        copy_agent
        build_spark_image
        update_spark_custom_with_app
        push_images
        deploy_k8s
        cleanup_docker
        echo "Setup complete!"
        ;;
    redeploy)
        setup_docker_env
        copy_agent
        build_spark_image
        update_spark_custom_with_app
        push_images
        deploy_k8s
        cleanup_docker
        echo "Redeploy complete!"
        ;;
    clean)
        cleanup_docker
        ;;
    *)
        usage
        ;;
esac
