#!/bin/bash

# This script creates a Kubernetes secret for AWS credentials
# Usage: ./setup-aws-credentials.sh <aws-access-key-id> <aws-secret-access-key>

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <aws-access-key-id> <aws-secret-access-key>"
    exit 1
fi

AWS_ACCESS_KEY_ID=$1
AWS_SECRET_ACCESS_KEY=$2

# Create the Kubernetes secret
kubectl create secret generic aws-credentials \
    --from-literal=aws-access-key-id=$AWS_ACCESS_KEY_ID \
    --from-literal=aws-secret-access-key=$AWS_SECRET_ACCESS_KEY \
    --dry-run=client -o yaml | kubectl apply -f -

echo "AWS credentials secret created/updated in Kubernetes."
