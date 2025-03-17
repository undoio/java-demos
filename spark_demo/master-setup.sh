#!/bin/bash
set -e

echo "Setting up Kubernetes environment..."

# Install minikube if not present
if ! command -v minikube &> /dev/null; then
    echo "Installing minikube..."
    curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
    sudo install minikube-linux-amd64 /usr/local/bin/minikube
    rm minikube-linux-amd64
fi

# Install kubectl if not present
if ! command -v kubectl &> /dev/null; then
    echo "Installing kubectl..."
    curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
    sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
    rm kubectl
fi

# Install Helm if not present
if ! command -v helm &> /dev/null; then
    echo "Installing Helm..."
    curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
fi

# Start minikube with required resources
echo "Starting minikube..."
minikube start --memory=8192 --cpus=4 --driver=docker

# Enable registry addon
echo "Enabling registry addon..."
minikube addons enable registry

# Install Spark Operator
echo "Installing Spark Operator..."
helm repo add spark-operator https://kubeflow.github.io/spark-operator
helm repo update

kubectl create namespace spark-operator
helm install spark-operator spark-operator/spark-operator \
    --namespace spark-operator \
    --set serviceAccounts.spark.create=true \
    --set webhook.enable=true

# Create service account for Spark
kubectl create serviceaccount spark
kubectl create clusterrolebinding spark-role --clusterrole=edit --serviceaccount=default:spark

# Run the setup script to build and push images
./deploy.sh setup
./deploy.sh redeploy

echo "Setup complete! Use 'kubectl get pods -A' to verify all components are running."
