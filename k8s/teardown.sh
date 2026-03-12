#!/usr/bin/env bash
# Tear down the Amazon microservices Kubernetes deployment
set -e
NAMESPACE="amazon"

echo "⚠️  This will delete ALL resources in the '$NAMESPACE' namespace."
read -p "Are you sure? (yes/no): " confirm
[[ "$confirm" == "yes" ]] || { echo "Aborted."; exit 0; }

kubectl delete namespace "$NAMESPACE" --grace-period=10
echo "✅ Namespace '$NAMESPACE' deleted. All resources removed."
