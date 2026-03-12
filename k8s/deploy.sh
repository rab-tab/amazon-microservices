#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# Amazon Microservices — Kubernetes Deploy Script
# Usage:
#   ./deploy.sh          — deploy everything
#   ./deploy.sh infra    — deploy only infrastructure (DB, Kafka, Redis)
#   ./deploy.sh services — deploy only microservices
#   ./deploy.sh monitor  — deploy only monitoring stack
# ─────────────────────────────────────────────────────────────────────

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE="amazon"

log() { echo -e "\033[1;34m[$(date '+%H:%M:%S')] $1\033[0m"; }
success() { echo -e "\033[1;32m[$(date '+%H:%M:%S')] ✅ $1\033[0m"; }
error() { echo -e "\033[1;31m[$(date '+%H:%M:%S')] ❌ $1\033[0m"; exit 1; }

check_prerequisites() {
    command -v kubectl &>/dev/null || error "kubectl not found"
    command -v docker &>/dev/null || error "docker not found"
    kubectl cluster-info &>/dev/null || error "Kubernetes cluster not reachable"
    success "Prerequisites OK"
}

build_images() {
    log "Building Docker images..."
    SERVICES=(api-gateway user-service product-service order-service payment-service notification-service)
    for svc in "${SERVICES[@]}"; do
        log "Building $svc..."
        (cd "$SCRIPT_DIR/../$svc" && mvn clean package -DskipTests -q)
        docker build -t "amazon/$svc:1.0.0" "$SCRIPT_DIR/../$svc"
        success "$svc image built"
    done
}

deploy_namespace() {
    log "Creating namespace..."
    kubectl apply -f "$SCRIPT_DIR/namespaces/namespace.yaml"
    success "Namespace 'amazon' ready"
}

deploy_secrets() {
    log "Applying secrets..."
    kubectl apply -f "$SCRIPT_DIR/secrets/secrets.yaml"
    success "Secrets applied"
}

deploy_configmaps() {
    log "Applying ConfigMaps..."
    kubectl apply -f "$SCRIPT_DIR/configmaps/configmaps.yaml"
    success "ConfigMaps applied"
}

deploy_infrastructure() {
    log "Deploying infrastructure (PostgreSQL, Redis, Kafka)..."
    kubectl apply -f "$SCRIPT_DIR/infrastructure/infrastructure.yaml"

    log "Waiting for PostgreSQL to be ready..."
    kubectl rollout status deployment/postgres -n "$NAMESPACE" --timeout=120s
    log "Waiting for Redis to be ready..."
    kubectl rollout status deployment/redis -n "$NAMESPACE" --timeout=60s
    log "Waiting for Kafka to be ready..."
    kubectl rollout status deployment/kafka -n "$NAMESPACE" --timeout=120s
    success "Infrastructure ready"
}

deploy_monitoring() {
    log "Deploying monitoring stack (Prometheus, Grafana, Zipkin, Kafka UI)..."
    kubectl apply -f "$SCRIPT_DIR/monitoring/monitoring.yaml"
    success "Monitoring stack deployed"
}

deploy_services() {
    log "Deploying microservices..."
    kubectl apply -f "$SCRIPT_DIR/services/services.yaml"

    SERVICES=(user-service product-service order-service payment-service notification-service api-gateway)
    for svc in "${SERVICES[@]}"; do
        log "Waiting for $svc..."
        kubectl rollout status deployment/"$svc" -n "$NAMESPACE" --timeout=180s
    done
    success "All microservices deployed"
}

deploy_hpa() {
    log "Applying HPA policies..."
    kubectl apply -f "$SCRIPT_DIR/hpa/hpa.yaml"
    success "HPA policies applied"
}

deploy_ingress() {
    log "Applying Ingress rules..."
    kubectl apply -f "$SCRIPT_DIR/ingress/ingress.yaml"
    success "Ingress rules applied"
}

print_status() {
    echo ""
    log "=== Deployment Status ==="
    kubectl get pods -n "$NAMESPACE" -o wide
    echo ""
    kubectl get services -n "$NAMESPACE"
    echo ""
    kubectl get hpa -n "$NAMESPACE"
    echo ""
    log "=== Access URLs ==="
    echo "  API Gateway:      http://$(kubectl get svc api-gateway -n $NAMESPACE -o jsonpath='{.spec.clusterIP}'):8080"
    echo "  Grafana:          http://grafana.amazon-internal.com  (admin/admin123)"
    echo "  Prometheus:       http://prometheus.amazon-internal.com"
    echo "  Zipkin:           http://zipkin.amazon-internal.com"
    echo "  Kafka UI:         http://kafka-ui.amazon-internal.com"
}

TARGET=${1:-all}

check_prerequisites

case "$TARGET" in
    infra)
        deploy_namespace; deploy_secrets; deploy_configmaps; deploy_infrastructure
        ;;
    services)
        deploy_services; deploy_hpa
        ;;
    monitor)
        deploy_monitoring
        ;;
    all|*)
        deploy_namespace
        deploy_secrets
        deploy_configmaps
        build_images
        deploy_infrastructure
        deploy_monitoring
        deploy_services
        deploy_hpa
        deploy_ingress
        print_status
        ;;
esac

success "Deployment complete!"
