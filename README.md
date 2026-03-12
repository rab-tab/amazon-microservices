# Amazon Microservices

Spring Boot microservices platform with Kafka event-driven architecture.

## Services

| Service | Port | Description |
|---|---|---|
| api-gateway | 8080 | JWT auth, routing, circuit breakers |
| user-service | 8081 | Registration, login, profiles |
| product-service | 8082 | Catalogue, stock management |
| order-service | 8083 | Order lifecycle, Saga pattern |
| payment-service | 8084 | Payment processing |
| notification-service | 8085 | Kafka-driven email/event notifications |

## Tech Stack

Java 21 · Spring Boot 3.2 · Spring Cloud Gateway · Apache Kafka · Redis · PostgreSQL · Resilience4j · Docker

## Local Development (without CI/CD)

```bash
cd docker
docker compose up -d
```

All services start with Kafka, Redis, PostgreSQL, Zipkin, and Prometheus.

## CI/CD

Managed by Jenkins. See the `Jenkinsfile` at the root of this repo.

On every push to `main`:
1. Detects which services changed
2. Builds and unit-tests only changed services
3. Builds Docker images tagged with git SHA
4. Pushes images to the local Docker registry
5. Triggers the `amazon-test-automation` pipeline with the image tag
