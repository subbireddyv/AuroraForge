# AuroraForge – Multi-Cloud Resilient Data Orchestration System

> **Principal-level architecture** demonstrating cloud-agnostic design, hybrid data pipelines,
> unified key management, active-active disaster recovery, and centralized observability across
> AWS and Azure — built with Java 21, Spring Boot 3.2, Apache Kafka, Apache Spark, and Crossplane.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Module Structure](#2-module-structure)
3. [Data Flow Diagrams](#3-data-flow-diagrams)
4. [Security Architecture](#4-security-architecture)
5. [Disaster Recovery State Machine](#5-disaster-recovery-state-machine)
6. [Observability Pipeline](#6-observability-pipeline)
7. [Technology Stack](#7-technology-stack)
8. [Design Decisions](#8-design-decisions)
9. [Local Development Setup](#9-local-development-setup)
10. [Building the Services](#10-building-the-services)
11. [Running Services Locally](#11-running-services-locally)
12. [Environment Variables Reference](#12-environment-variables-reference)
13. [API Reference](#13-api-reference)
14. [Infrastructure Provisioning](#14-infrastructure-provisioning)
15. [Cloud Deployment – AWS](#15-cloud-deployment--aws)
16. [Cloud Deployment – Azure](#16-cloud-deployment--azure)
17. [Crossplane Cloud-Agnostic Layer](#17-crossplane-cloud-agnostic-layer)
18. [Kubernetes Deployment](#18-kubernetes-deployment)
19. [Resilience Configuration](#19-resilience-configuration)
20. [Security Hardening](#20-security-hardening)
21. [Observability Runbook](#21-observability-runbook)
22. [Disaster Recovery Runbook](#22-disaster-recovery-runbook)
23. [Contributing](#23-contributing)

---

## 1. Architecture Overview

```
╔══════════════════════════════════════════════════════════════════════════════════════════╗
║                       AuroraForge – System Architecture                                  ║
╠══════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                          ║
║   ┌──────────────────────────────────────────────────────────────────────────────────┐  ║
║   │                         CLIENT / INGESTION TIER                                  │  ║
║   │         REST clients · Event producers · CDC (Debezium on PostgreSQL WAL)        │  ║
║   └───────────────┬──────────────────────────────────────┬───────────────────────────┘  ║
║                   │  HTTP/JWT                            │  Kafka events                ║
║                   ▼                                      ▼                              ║
║   ┌───────────────────────────┐          ┌──────────────────────────────────────────┐  ║
║   │   Auth Service (:8085)    │          │         Apache Kafka Cluster              │  ║
║   │   · RS256 JWT issuance    │          │         (3 brokers, ZooKeeper)            │  ║
║   │   · Token blacklist       │          │   ┌──────────────────────────────────┐   │  ║
║   │     (Redis)               │          │   │  Schema Registry (Avro)          │   │  ║
║   │   · Bucket4j rate limit   │          │   │  BACKWARD_TRANSITIVE compat.     │   │  ║
║   │   · JWKS endpoint         │          │   └──────────────────────────────────┘   │  ║
║   └───────────────────────────┘          │   ┌──────────────────────────────────┐   │  ║
║                                          │   │  Kafka Connect + Debezium CDC    │   │  ║
║   ┌───────────────────────────┐          │   │  (PostgreSQL logical replication) │   │  ║
║   │  Ingestion Svc (:8081)    │─────────▶│   └──────────────────────────────────┘   │  ║
║   │  · Validates + encrypts   │          │   ┌──────────────────────────────────┐   │  ║
║   │  · Publishes Avro events  │          │   │  Dead Letter Queue topics        │   │  ║
║   │  · S3 / Blob raw storage  │          │   └──────────────────────────────────┘   │  ║
║   └───────────────────────────┘          └──────────────────┬───────────────────────┘  ║
║                                                             │                           ║
║                             ┌───────────────────────────────┤                           ║
║                             │                               │                           ║
║                             ▼                               ▼                           ║
║   ┌──────────────────────────────────┐   ┌──────────────────────────────────────────┐  ║
║   │   Processing Service (:8082)     │   │   Sync Service (:8083)                   │  ║
║   │   · Kafka Streams (exactly-once) │   │   · Cross-cloud replication              │  ║
║   │   · Apache Spark batch jobs      │   │   · 5-strategy conflict resolution       │  ║
║   │   · Feature engineering / agg.   │   │   · DR state machine (5 states)          │  ║
║   └──────────────────────────────────┘   │   · DLQ retry (exponential backoff)      │  ║
║                                          │   · SHA-256 consistency verification     │  ║
║   ┌──────────────────────────────────┐   └──────────────────────────────────────────┘  ║
║   │   Key Mgmt Service (:8084)       │                    │                            ║
║   │   · Unified KMS / Key Vault API  │                    │ replicate                  ║
║   │   · 4-tier data classification   │         ┌──────────┴──────────┐                ║
║   │   · 90-day auto key rotation     │         │                     │                ║
║   │   · In-memory DEK cache          │         ▼                     ▼                ║
║   └──────────────────────────────────┘                                                 ║
║                                          ┌──────────────┐  ┌────────────────────────┐  ║
║   ┌──────────────────────────────────┐   │  AWS REGION  │  │    AZURE REGION        │  ║
║   │   Observability Library          │   │              │  │                        │  ║
║   │   (auto-configured shared lib)   │   │  EKS         │  │  AKS                   │  ║
║   │   · OTel traces → Jaeger/Tempo   │   │  RDS PG 16   │  │  Cosmos DB             │  ║
║   │   · Micrometer → Prometheus      │   │  KMS (CMK)   │  │  Key Vault (CMK)       │  ║
║   │   · Structured JSON logs         │   │  S3 (versnd) │  │  Azure Blob            │  ║
║   │   · MDC: reqId/tenantId/traceId  │   └──────┬───────┘  └───────┬────────────────┘  ║
║   │   · @AuditLog AOP → audit.log    │          │  PrivateLink/VPN │                   ║
║   │   · CircuitBreaker event logging │          └──────────◄►───────┘                  ║
║   └──────────────────────────────────┘                                                  ║
║                                                                                          ║
║   ┌──────────────────────────────────────────────────────────────────────────────────┐  ║
║   │           INFRASTRUCTURE ABSTRACTION (Crossplane + Terraform)                    │  ║
║   │   Crossplane XRDs → Compositions → AWS RDS / Azure Cosmos DB / S3 / Blob        │  ║
║   │   Terraform: VPC/VNet, EKS/AKS, IAM/RBAC, KMS/Key Vault, networking            │  ║
║   └──────────────────────────────────────────────────────────────────────────────────┘  ║
╚══════════════════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. Module Structure

```
services/
├── pom.xml                            ← parent POM (Spring Boot 3.2.5, Java 21)
│
├── auroraforge-core/                  ← Domain model + port interfaces (zero Spring deps)
│   └── domain/
│       ├── model/                     ← TenantId, EventRecord, VectorClock, EncryptionContext …
│       └── port/                      ← CloudObjectStoragePort, KeyManagementPort, EventPublisherPort
│
├── auroraforge-observability/         ← Shared auto-configured library
│   ├── audit/                         ← AuditEventType, AuditEvent, AuditLogger, @AuditLog, AuditLogAspect
│   ├── aspect/TracingAspect           ← OTel spans on application + infrastructure methods
│   ├── kafka/                         ← KafkaMdcProducerInterceptor, KafkaMdcConsumerInterceptor
│   ├── logging/                       ← MdcRequestContextFilter, HttpRequestLoggingFilter
│   ├── metrics/AuroraForgeMetrics     ← Custom Micrometer gauges/counters
│   ├── resilience/CircuitBreakerEventLogger
│   ├── security/SecurityHeadersFilter ← HSTS, CSP, X-Frame-Options, Permissions-Policy …
│   ├── config/ObservabilityAutoConfiguration  ← single META-INF entry point
│   └── resources/logback-base.xml     ← shared appenders (CONSOLE / ASYNC_JSON / AUDIT)
│
├── auroraforge-auth/                  ← JWT auth server + shared SecurityFilterChain
│   ├── infrastructure/jwt/            ← RS256 key provider, JwtAuthenticationConverter
│   ├── infrastructure/ratelimit/      ← Bucket4j per-tenant rate limiter
│   └── infrastructure/security/       ← AuroraForgeSecurityConfig (Spring Security DSL)
│
├── auroraforge-ingestion/             ← REST event ingestion → Kafka + object storage
│   ├── infrastructure/aws/            ← AwsS3StorageAdapter (@CircuitBreaker + @Bulkhead + @Retry)
│   ├── infrastructure/azure/          ← AzureBlobStorageAdapter (same resilience decorators)
│   └── infrastructure/kafka/          ← KafkaEventPublisher (exactly-once, Avro)
│
├── auroraforge-processing/            ← Kafka Streams topology + Spark batch jobs
│
├── auroraforge-sync/                  ← Cross-cloud replication + DR orchestration
│   ├── application/service/
│   │   ├── CrossCloudSyncService      ← Kafka consumer → replicate to both clouds
│   │   ├── DisasterRecoveryCoordinator← DR state machine + failover/recovery
│   │   ├── ConsistencyVerificationService ← SHA-256 payload comparison
│   │   └── ReplicationLagMonitor      ← RPO tracking + Micrometer lag gauges
│   ├── domain/model/
│   │   ├── DisasterRecoveryState      ← HEALTHY/DEGRADED/FAILOVER_INITIATED/FAILOVER_ACTIVE/RECOVERING
│   │   ├── ConflictStrategy           ← LAST_WRITE_WINS/HIGHEST_VECTOR_CLOCK/CLOUD_PRIORITY/FIELD_MERGE/MANUAL_REVIEW
│   │   ├── CloudHealth                ← reachable, latencyMs, errorRatePercent, pendingSyncCount
│   │   └── VectorClock                ← causal ordering for conflict detection
│   ├── infrastructure/resolver/       ← MultiStrategyConflictResolver
│   ├── infrastructure/persistence/    ← DlqRecordEntity, DlqRecordRepository (PostgreSQL)
│   ├── infrastructure/scheduler/      ← DlqRetryScheduler (exponential backoff)
│   └── presentation/                  ← DisasterRecoveryController (/api/v1/dr/**)
│
└── auroraforge-keymgmt/               ← Unified key management
    ├── infrastructure/aws/             ← AwsKmsAdapter
    └── infrastructure/azure/           ← AzureKeyVaultAdapter
```

**Module dependency graph:**

```
          ┌──────────────────────────┐
          │    auroraforge-core       │  (no Spring – pure domain)
          └────────────┬─────────────┘
                       │  depended on by all
       ┌───────────────┼───────────────────┐
       │               │                   │
       ▼               ▼                   ▼
auroraforge-    auroraforge-        auroraforge-
observability      auth               keymgmt
       │              │                   │
       │  (auto-cfg)  │  (auto-cfg)       │
       └──────┬────────┘                  │
              │  consumed by              │
   ┌──────────┼──────────┬────────────────┘
   │          │          │
   ▼          ▼          ▼
auroraforge  auroraforge auroraforge
-ingestion   -processing   -sync
```

---

## 3. Data Flow Diagrams

### 3.1 Real-Time Ingestion Path

```
HTTP POST /api/v1/tenants/{tid}/events
          │
          ▼  JWT validated by AuroraForgeSecurityConfig
    IngestionService
          │
          ├── 1. Validate (Jakarta Bean Validation)
          ├── 2. Encrypt payload (KeyManagementPort → KMS / Key Vault)
          ├── 3. Persist EventRecord to PostgreSQL (status = PROCESSING)
          ├── 4. Upload raw bytes to S3 / Azure Blob
          └── 5. Publish Avro message to Kafka
                 key=tenantId, headers=MDC (X-Request-ID, X-Trace-ID, X-Tenant-ID …)
                         │
                         ▼
             auroraforge.events.{tenant}.raw
                         │
             ┌───────────┴────────────────┐
             │                            │
             ▼                            ▼
  ProcessingService             CrossCloudSyncService
  (Kafka Streams)               (Kafka consumer)
  · windowed aggregation        · replicate to AWS (S3 + RDS)
  · stateful joins              · replicate to Azure (Cosmos DB + Blob)
  · feature engineering         · conflict resolution on concurrent writes
             │                            │
             ▼                            ▼
  auroraforge.events.{tenant}.processed  DLQ (PostgreSQL sync_dlq) if failure
             │
             ▼
  Spark batch (periodic)
  · aggregation analytics
  · archive to cold tier
```

### 3.2 Conflict Resolution Flow

```
Concurrent write detected (vector clocks diverged)
          │
          ▼
MultiStrategyConflictResolver.resolve(local, remote, aggregateType)
          │
          ├── aggregateType == TENANT_CONFIG   → CLOUD_PRIORITY (primary cloud wins)
          ├── aggregateType == AUDIT_RECORD    → LAST_WRITE_WINS (wall clock)
          ├── aggregateType == RESTRICTED_DATA → MANUAL_REVIEW → DLQ (human sign-off)
          ├── default                          → HIGHEST_VECTOR_CLOCK
          │                                      fallback to LWW on CONCURRENT
          └── explicit FIELD_MERGE config      → JSON field-level 3-way merge
                                                 LWW per-field on conflict
          │
          ▼  (if MANUAL_REVIEW or unresolvable)
    DlqRecordEntity persisted (PostgreSQL sync_dlq)
          │
          ▼  DlqRetryScheduler runs every 60 s
    Exponential backoff: 30 × 2^attempt, capped at 3 600 s
    Max 5 attempts → status = EXHAUSTED, metric counter incremented
```

### 3.3 CDC Path (Debezium)

```
PostgreSQL WAL (wal_level=logical, max_replication_slots=10)
          │
          ▼
Debezium PostgreSQL Connector (Kafka Connect)
          │
          ▼
auroraforge.cdc.{schema}.{table}   (Avro, schema-registry-encoded)
          │
          ▼
CrossCloudSyncService Kafka consumer
          └── Sync to Cosmos DB / S3 via the same conflict resolution path
```

---

## 4. Security Architecture

```
─── HTTP Request Lifecycle ──────────────────────────────────────────────────────

Incoming request
      │
      ▼  Ordered.HIGHEST_PRECEDENCE
 MdcRequestContextFilter
   · X-Request-ID  → MDC "requestId"  (generates UUID if absent)
   · /tenants/{id}/… → MDC "tenantId"
   · X-Forwarded-User → MDC "userId"
   · OTel traceId / spanId → MDC (fallback 0000… if no active span)
   · Echoes X-Request-ID back in response header
      │
      ▼  +1
 SecurityHeadersFilter
   · Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
   · Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; …
   · X-Frame-Options: DENY
   · X-Content-Type-Options: nosniff
   · Referrer-Policy: no-referrer
   · Permissions-Policy: camera=(), microphone=(), geolocation=() …
   · Cache-Control: no-store
      │
      ▼  +2
 HttpRequestLoggingFilter
   · structured access log (method, uri, status, durationMs, remoteAddr)
   · slow-request WARN if > 2 000 ms
   · sanitized JSON body snippet at DEBUG (truncated to 2 KB)
      │
      ▼  before UsernamePasswordAuthenticationFilter
 TenantRateLimitFilter  (Bucket4j)
   · bucket key = tenantId from path, else "global"
   · 429 + X-RateLimit-Remaining + Retry-After: 60 on exhaustion
      │
      ▼
 Spring Security FilterChain
   · Stateless, CSRF disabled, CORS from SecurityProperties
   · JWT Resource Server: NimbusJwtDecoder (RS256, RsaKeyProvider public key)
   · JwtAuthenticationConverter: "roles" claim → GrantedAuthority list
   · Spring Security .headers() DSL: HSTS, frameOptions=DENY, nosniff, cacheControl

─── Route Authorization Matrix ─────────────────────────────────────────────────

  POST  /auth/token, /auth/refresh       → permitAll
  GET   /.well-known/jwks.json           → permitAll
  GET   /actuator/health, /actuator/info → permitAll  (k8s probes)
  *     /actuator/**                     → ROLE_PLATFORM_OPS
  POST  /api/v1/tenants/*/events         → ROLE_DATA_INGEST
  GET   /api/v1/tenants/*/events/**      → ROLE_DATA_QUERY or ROLE_ADMIN
  POST  /api/v1/keys/rotate/**           → ROLE_KEY_MANAGER or ROLE_ADMIN
  *     /api/v1/keys/**                  → ROLE_KEY_MANAGER or ROLE_ADMIN
  *     /api/v1/dr/**                    → ROLE_PLATFORM_OPS or ROLE_ADMIN
  POST  /auth/logout                     → authenticated
  **                                     → authenticated (catch-all deny)

─── Key Management ──────────────────────────────────────────────────────────────

  Data Class    Key Rotation Schedule         DEK Cache TTL
  ──────────    ───────────────────────────   ─────────────
  PUBLIC        Sundays 02:00 UTC            5 min
  INTERNAL      Sundays 02:00 UTC            5 min
  CONFIDENTIAL  1st of month 03:00 UTC       60 s
  RESTRICTED    1st of month 04:00 UTC       60 s

  Cloud routing:
    CLOUD_PROVIDER=AWS   → AwsKmsAdapter   (GenerateDataKey / Decrypt)
    CLOUD_PROVIDER=AZURE → AzureKeyVaultAdapter (wrapKey / unwrapKey)
    CLOUD_PROVIDER=LOCAL → HashiCorp Vault (dev mode)
```

---

## 5. Disaster Recovery State Machine

```
                    ┌──────────────────┐
                    │    HEALTHY        │◄─────────────────────────────┐
                    │  (initial state)  │                              │
                    └────────┬──────────┘                    completeRecovery()
                             │                               (state must be RECOVERING)
                    primary cloud degrades                            │
                    (unreachable OR errorRate > 5%         ┌──────────┴────────┐
                    OR latencyMs > 5 000 ms)               │    RECOVERING     │
                             │                             │  primary back,    │
                             ▼                             │  draining DLQ     │
                    ┌──────────────────┐                   └─────────▲─────────┘
                    │    DEGRADED       │                             │
                    │  degradedSince    │                   primary cloud healthy
                    │  set; threshold   │                   after FAILOVER_ACTIVE
                    │  not yet elapsed  │
                    └────────┬──────────┘
                             │
                   degradedThresholdSeconds (60 s default)
                   elapsed without recovery
                             │
                             ▼
                    ┌──────────────────┐       ┌─────────────────────────┐
                    │FAILOVER_INITIATED│──────▶│    FAILOVER_ACTIVE       │
                    │  triggerFailover │       │  activeCloud = target    │
                    │  idempotent      │       │  failoverActiveSince set  │
                    └──────────────────┘       │  DR gauge = 3            │
                                               └─────────────────────────┘

  Gauge: auroraforge.dr.state
    0 = HEALTHY  1 = DEGRADED  2 = FAILOVER_INITIATED  3 = FAILOVER_ACTIVE  4 = RECOVERING

  Health check: every 30 s  (auroraforge.dr.health-check-interval-seconds)
  RPO threshold: 300 s — RPO BREACH logged at ERROR when exceeded
```

**Conflict strategies by aggregate type (default config):**

| Aggregate Type     | Strategy               | Rationale                                  |
|--------------------|------------------------|--------------------------------------------|
| `TENANT_CONFIG`    | `CLOUD_PRIORITY`       | Primary cloud is authoritative for config  |
| `AUDIT_RECORD`     | `LAST_WRITE_WINS`      | Immutable records; LWW safe                |
| `RESTRICTED_DATA`  | `MANUAL_REVIEW`        | Compliance requires human sign-off         |
| *(default)*        | `HIGHEST_VECTOR_CLOCK` | Causal successor wins; LWW tiebreak        |

---

## 6. Observability Pipeline

```
─── Log Emission ─────────────────────────────────────────────────────────────────

  Every log line (logstash-logback-encoder, JSON):
    @timestamp, level, logger, thread, message
    + MDC: requestId, tenantId, userId, traceId, spanId, service, version

  Profile selection (each service logback-spring.xml → includes logback-base.xml):
    dev / default  → CONSOLE appender (colored, human-readable)
    prod / staging → ASYNC_JSON appender (2 048 queue, zero-drop policy)
    file           → ASYNC_JSON + rolling file (ASYNC_FILE, 30-day retention)
    always         → AUDIT appender → audit.log (90-day retention, separate file)

  Sensitive field masking: "password", "token", "secret" → "****"

─── Distributed Traces ───────────────────────────────────────────────────────────

  TracingAspect intercepts:
    io.auroraforge..application.service.*.*(..)    → INTERNAL span
    io.auroraforge..infrastructure..*Adapter.*(..) → CLIENT span
    *Publisher.*(..)                                → PRODUCER span

  Kafka MDC propagation:
    Producer: KafkaMdcProducerInterceptor injects X-Request-ID, X-Tenant-ID,
              X-Trace-ID, X-Span-ID, X-User-ID as Kafka record headers
    Consumer: KafkaMdcConsumerInterceptor restores MDC from those headers

  Export: OTLP gRPC → otel-collector:4317
  Fan-out: Jaeger (traces) / Prometheus (metrics) / CloudWatch / Azure Monitor

─── Metrics (Micrometer → Prometheus) ───────────────────────────────────────────

  Custom metrics:
    auroraforge.dr.state                       gauge   DR state ordinal (0–4)
    auroraforge.replication.lag_seconds{cloud} gauge   per-cloud replication lag
    auroraforge.dlq.exhausted_total            gauge   records at max retry
    auroraforge.conflict.resolved{strategy}    counter conflicts resolved per strategy
    auroraforge.consistency.diverged{reason}   counter consistency check divergences
    auroraforge.dlq.retry{result}              counter DLQ retry outcomes

  CircuitBreakerEventLogger emits structured log on every CB transition:
    circuit-breaker-open (WARN, includes failureRate%)
    circuit-breaker-reset (INFO)
    circuit-breaker-call-rejected (WARN)

─── Actuator Ports ──────────────────────────────────────────────────────────────

  Service       App Port   Actuator Port   Exposed endpoints
  ─────────     ────────   ─────────────   ─────────────────────────────────────
  auth          8085       (shared)        health, info, metrics, prometheus
  ingestion     8081       8091            health, info, metrics, prometheus,
                                           loggers, threaddump, heapdump
  processing    8082       8092            health, info, metrics, prometheus
  sync          8083       8093            health, info, metrics, prometheus
  keymgmt       8084       8094            health, info, metrics, prometheus
```

---

## 7. Technology Stack

| Layer         | Technology                        | Version      | Rationale                                            |
|---------------|-----------------------------------|--------------|------------------------------------------------------|
| Runtime       | Java + Spring Boot                | 21 / 3.2.5   | Virtual threads (Loom), records, pattern matching    |
| Streaming     | Apache Kafka (3-broker)           | 3.7.0        | Exactly-once semantics, durable distributed log      |
| Batch         | Apache Spark                      | 3.5.1        | In-memory DAG, native S3/Blob support                |
| CDC           | Debezium on Kafka Connect         | 2.5.0        | Log-based CDC, zero-impact on source DB              |
| Primary DB    | PostgreSQL                        | 16.2         | ACID, JSONB, logical replication for CDC             |
| Cache         | Redis + Sentinel                  | 7.2.4        | Token blacklist, rate-limit counters                 |
| Object Store  | MinIO (local) / S3 + Blob (cloud) | —            | Tenant-scoped key-prefix isolation strategy          |
| Secrets       | Vault (local) / KMS + Key Vault   | 1.16.0 / —   | Unified `KeyManagementPort` per cloud                |
| Schema        | Confluent Schema Registry         | 7.6.1        | Avro, `BACKWARD_TRANSITIVE` compatibility            |
| Resilience    | Resilience4j                      | 2.2.0        | CB + Retry + Bulkhead + TimeLimiter per instance     |
| Security      | Spring Security + Nimbus JOSE+JWT | 3.2 / 9.37.3 | RS256 JWT, RBAC, Redis token blacklist               |
| Observability | OTel SDK + Micrometer + Logstash  | 1.36.0       | Vendor-neutral three-pillar telemetry                |
| IaC           | Terraform + Crossplane            | 1.7+ / 1.15  | Static infra + dynamic workload resources            |
| Containers    | Docker + Kubernetes (EKS + AKS)   | —            | Identical base manifests, cloud-specific overlays    |
| Mapping       | MapStruct + Lombok                | 1.5.5 / 1.18 | Compile-time mappers, annotation-driven boilerplate  |
| Testing       | Testcontainers + ArchUnit         | 1.19.7 / 1.3 | Real containers, architecture fitness functions      |

---

## 8. Design Decisions

### Active-Active Multi-Cloud
Both AWS and Azure serve live traffic simultaneously. Cosmos DB multi-region write + S3 cross-region replication provides RPO ≈ 0 under normal conditions. `MultiStrategyConflictResolver` handles concurrent writes using five strategies dispatched by aggregate type — eliminating the failover latency of a single-primary design.

**Trade-off**: Higher operational complexity. Mitigated by the Crossplane abstraction layer, a single `DisasterRecoveryController` REST API, and unified Grafana dashboards.

### Hexagonal Architecture (Ports & Adapters)
`auroraforge-core` contains only domain models and port interfaces with zero Spring dependencies. Each service module provides adapter implementations. The `CloudObjectStoragePort` has AWS S3, Azure Blob, and MinIO adapters behind the same interface, making the storage layer swappable without touching domain logic.

### Single Auto-Configured Observability Library
`auroraforge-observability` ships as a Spring Boot auto-configured library. Services gain structured logging, OTel tracing, Micrometer metrics, security headers, audit logging, and Kafka MDC propagation via a single Maven dependency and zero `@Configuration` boilerplate. One `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` entry is the only registration required.

### DLQ-First Failure Handling
Every Kafka consumer failure, CB trip, and bulkhead exhaustion routes the payload to the PostgreSQL `sync_dlq` table — not a Kafka DLQ topic. This survives broker restarts, enables human review for `MANUAL_REVIEW` conflicts, and supports per-record retry with exponential backoff without inflating Kafka consumer-group lag.

### Schema Evolution via Schema Registry
All Kafka messages use Avro with `BACKWARD_TRANSITIVE` compatibility enforced at the registry. Schema changes that would break existing consumers are rejected before reaching production.

---

## 9. Local Development Setup

### Prerequisites

| Tool           | Min Version | Install                                           |
|----------------|-------------|---------------------------------------------------|
| Docker Desktop | 4.25        | https://docs.docker.com/desktop/                  |
| Docker Compose | 2.24        | Bundled with Docker Desktop                       |
| JDK            | 21          | `sdk install java 21-tem` (SDKMAN) or Temurin 21  |
| Maven          | 3.9         | `sdk install maven 3.9.6`                         |
| kubectl        | 1.29        | https://kubernetes.io/docs/tasks/tools/           |
| Terraform      | 1.7         | https://developer.hashicorp.com/terraform/install |
| Helm           | 3.14        | https://helm.sh/docs/intro/install/               |
| AWS CLI        | 2.x         | Cloud deployment only                             |
| Azure CLI      | 2.59        | Cloud deployment only                             |

### Step 1 — Clone and configure

```bash
git clone https://github.com/your-org/auroraforge.git
cd auroraforge

cp .env.example .env
# Edit .env — at minimum set POSTGRES_PASSWORD, REDIS_PASSWORD
```

### Step 2 — Start the infrastructure stack

```bash
docker compose up -d

# Full expected output of `docker compose ps` once healthy:
#
#   NAME                              STATUS          PORTS
#   auroraforge-zookeeper             healthy         0.0.0.0:2181->2181/tcp
#   auroraforge-kafka-1               healthy         0.0.0.0:9092->9092/tcp, 0.0.0.0:29092->29092/tcp
#   auroraforge-kafka-2               healthy         0.0.0.0:9093->9093/tcp, 0.0.0.0:29093->29093/tcp
#   auroraforge-kafka-3               healthy         0.0.0.0:9094->9094/tcp, 0.0.0.0:29094->29094/tcp
#   auroraforge-schema-registry       healthy         0.0.0.0:8081->8081/tcp
#   auroraforge-kafka-connect         healthy         0.0.0.0:8083->8083/tcp   (≈2 min startup)
#   auroraforge-kafka-ui              running         0.0.0.0:8080->8080/tcp
#   auroraforge-spark-master          healthy         0.0.0.0:7077->7077, 0.0.0.0:8088->8080/tcp
#   auroraforge-spark-worker-1        running         0.0.0.0:8089->8081/tcp
#   auroraforge-spark-worker-2        running         0.0.0.0:8090->8081/tcp
#   auroraforge-postgresql            healthy         0.0.0.0:5432->5432/tcp
#   auroraforge-redis                 healthy         0.0.0.0:6379->6379/tcp
#   auroraforge-redis-sentinel        running         0.0.0.0:26379->26379/tcp
#   auroraforge-minio                 healthy         0.0.0.0:9000->9000, 0.0.0.0:9001->9001/tcp
#   auroraforge-minio-init            exited (0)      (one-shot)
#   auroraforge-vault                 healthy         0.0.0.0:8200->8200/tcp
#   auroraforge-otel-collector        healthy         0.0.0.0:4317-4318->4317-4318/tcp
#   auroraforge-prometheus            healthy         0.0.0.0:9090->9090/tcp
#   auroraforge-grafana               healthy         0.0.0.0:3000->3000/tcp
#   auroraforge-jaeger                healthy         0.0.0.0:16686->16686/tcp
```

### Step 3 — Verify infrastructure UIs

| Service         | URL                               | Credentials                    |
|-----------------|-----------------------------------|--------------------------------|
| Kafka UI        | http://localhost:8080             | No auth (dev)                  |
| Schema Registry | http://localhost:8081/subjects    | No auth                        |
| Kafka Connect   | http://localhost:8083/connectors  | No auth                        |
| Spark UI        | http://localhost:8088             | No auth                        |
| MinIO Console   | http://localhost:9001             | minioadmin / minioadmin        |
| Vault UI        | http://localhost:8200/ui          | Token: `auroraforge-dev`       |
| Prometheus      | http://localhost:9090             | No auth                        |
| Grafana         | http://localhost:3000             | admin / admin                  |
| Jaeger UI       | http://localhost:16686            | No auth                        |

### Step 4 — Initialise Kafka topics and Debezium connector

```bash
# Create application topics (auto-create is disabled on the cluster)
./scripts/init-kafka.sh
# Topics created:
#   auroraforge.events.raw          partitions=12  rf=3
#   auroraforge.events.processed    partitions=12  rf=3
#   auroraforge.events.raw.DLQ      partitions=3   rf=3

# Register the Debezium PostgreSQL connector
curl -X POST http://localhost:8083/connectors \
  -H 'Content-Type: application/json' \
  -d @config/debezium/postgresql-connector.json

# Confirm connector state is RUNNING
curl -s http://localhost:8083/connectors/auroraforge-postgresql/status | jq .connector.state
```

### Step 5 — Seed Vault secrets (local dev)

```bash
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=auroraforge-dev

vault kv put secret/auroraforge/postgres \
  password="auroraforge_dev_secret"

vault kv put secret/auroraforge/redis \
  password="redis_dev_secret"

# Generate a local RSA key pair for JWT signing
vault kv put secret/auroraforge/auth \
  jwt_rsa_private_key="$(openssl genrsa 4096 2>/dev/null | base64 -w0)" \
  jwt_rsa_public_key="$(openssl rsa -pubout 2>/dev/null | base64 -w0)"
```

---

## 10. Building the Services

```bash
# Build all modules, skip tests
mvn -f services/pom.xml clean install -DskipTests

# Build with unit tests (no containers required)
mvn -f services/pom.xml clean install

# Build with integration tests (requires docker compose up -d)
mvn -f services/pom.xml clean verify -Pfailsafe

# Build a single module
mvn -f services/auroraforge-sync/pom.xml clean package -DskipTests

# Build OCI images via Paketo Buildpacks (no Dockerfile required)
mvn -f services/pom.xml spring-boot:build-image -DskipTests
# Produces:
#   auroraforge/auroraforge-auth:1.0.0-SNAPSHOT
#   auroraforge/auroraforge-ingestion:1.0.0-SNAPSHOT
#   auroraforge/auroraforge-processing:1.0.0-SNAPSHOT
#   auroraforge/auroraforge-sync:1.0.0-SNAPSHOT
#   auroraforge/auroraforge-keymgmt:1.0.0-SNAPSHOT

# Run architecture fitness tests (ArchUnit)
mvn -f services/pom.xml test -Dtest="*ArchTest"
```

> **Java 21 note:** `--enable-preview` is set in the parent POM for both the compiler and
> Surefire/Failsafe plugins. Use exactly JDK 21 — preview features are release-bound.

---

## 11. Running Services Locally

Recommended startup order: **auth → keymgmt → ingestion → processing → sync**

```bash
# Auth Service
java --enable-preview \
  -jar services/auroraforge-auth/target/auroraforge-auth-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=docker

# Key Management Service
java --enable-preview \
  -jar services/auroraforge-keymgmt/target/auroraforge-keymgmt-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=docker

# Ingestion Service (aws or azure profile selects the storage adapter)
java --enable-preview \
  -jar services/auroraforge-ingestion/target/auroraforge-ingestion-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=docker,aws

# Processing Service
java --enable-preview \
  -jar services/auroraforge-processing/target/auroraforge-processing-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=docker

# Sync Service
java --enable-preview \
  -jar services/auroraforge-sync/target/auroraforge-sync-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=docker
```

**Service port reference:**

| Service     | App Port | Actuator Port |
|-------------|----------|---------------|
| auth        | 8085     | —             |
| ingestion   | 8081     | 8091          |
| processing  | 8082     | 8092          |
| sync        | 8083     | 8093          |
| keymgmt     | 8084     | 8094          |

**Verify all services are healthy:**

```bash
for port in 8091 8092 8093 8094; do
  echo -n "Actuator :$port → "
  curl -s http://localhost:$port/actuator/health | jq -r .status
done
```

**Obtain a JWT and ingest a test event:**

```bash
# Get access token
TOKEN=$(curl -s -X POST http://localhost:8085/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"dev-user","password":"dev-password"}' | jq -r .accessToken)

# Ingest an event
curl -X POST http://localhost:8081/api/v1/tenants/t1/events \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"aggregateId":"agg-001","eventType":"ORDER_PLACED","payload":{"amount":99.99}}'
```

---

## 12. Environment Variables Reference

### All services

| Variable                  | Default                              | Description                              |
|---------------------------|--------------------------------------|------------------------------------------|
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka-1:9092,kafka-2:9093,...`      | Kafka broker list                        |
| `SCHEMA_REGISTRY_URL`     | `http://schema-registry:8081`        | Confluent Schema Registry URL            |
| `POSTGRES_HOST`           | `localhost`                          | PostgreSQL host                          |
| `POSTGRES_PORT`           | `5432`                               | PostgreSQL port                          |
| `POSTGRES_DB`             | `auroraforge`                        | Database name                            |
| `POSTGRES_USER`           | `auroraforge`                        | Database user                            |
| `POSTGRES_PASSWORD`       | `auroraforge_dev_secret`             | **Must override in production**          |
| `CLOUD_PROVIDER`          | `LOCAL`                              | `LOCAL` / `AWS` / `AZURE`               |
| `OTEL_EXPORTER_ENDPOINT`  | `http://otel-collector:4317`         | OTLP gRPC endpoint                       |
| `DEPLOYMENT_ENV`          | `local`                              | `local` / `staging` / `production`       |

### Auth Service

| Variable               | Default                         | Description                               |
|------------------------|---------------------------------|-------------------------------------------|
| `AUTH_PORT`            | `8085`                          | HTTP listen port                          |
| `REDIS_HOST`           | `localhost`                     | Redis host (token blacklist)              |
| `REDIS_PORT`           | `6379`                          | Redis port                                |
| `REDIS_PASSWORD`       | `redis_dev_secret`              | Redis auth password                       |
| `JWT_ISSUER`           | `https://auth.auroraforge.io`   | JWT `iss` claim                           |
| `JWT_ACCESS_EXPIRY`    | `900`                           | Access token TTL seconds (15 min)         |
| `JWT_REFRESH_EXPIRY`   | `86400`                         | Refresh token TTL seconds (24 h)          |
| `JWT_RSA_GENERATE`     | `true`                          | Auto-generate RSA key pair on startup     |
| `JWT_KEY_ID`           | `auroraforge-auth-key-1`        | Key ID in JWT header (`kid` claim)        |
| `RATE_LIMIT_TPM`       | `60`                            | Bucket4j tokens per minute per tenant     |
| `RATE_LIMIT_BURST`     | `10`                            | Burst capacity above steady rate          |
| `CORS_ORIGIN`          | `http://localhost:3000`         | Allowed CORS origin                       |
| `BCRYPT_STRENGTH`      | `12`                            | BCrypt work factor (use 10 for dev speed) |

### Ingestion Service

| Variable              | Default                  | Description                          |
|-----------------------|--------------------------|--------------------------------------|
| `SERVER_PORT`         | `8081`                   | HTTP listen port                     |
| `AWS_REGION`          | `us-east-1`              | AWS region for S3 + KMS              |
| `AWS_S3_RAW_BUCKET`   | `auroraforge-raw`        | S3 bucket for raw event storage      |
| `AWS_KMS_KEY_ALIAS`   | —                        | KMS CMK alias (required on AWS)      |

### Sync Service

| Variable                  | Default                                                | Description                   |
|---------------------------|--------------------------------------------------------|-------------------------------|
| `DATASOURCE_URL`          | `jdbc:postgresql://localhost:5432/auroraforge`         | DLQ database JDBC URL         |
| `DATASOURCE_USER`         | `auroraforge`                                          | DLQ database user             |
| `DATASOURCE_PASSWORD`     | `changeme`                                             | DLQ database password         |
| `AWS_S3_PROCESSED_BUCKET` | `auroraforge-processed`                                | S3 bucket for synced data     |
| `COSMOS_DB_ENDPOINT`      | `https://auroraforge-cosmos.documents.azure.com:443/`  | Cosmos DB account endpoint    |

### Key Management Service

| Variable              | Default   | Description                                           |
|-----------------------|-----------|-------------------------------------------------------|
| `SERVER_PORT`         | `8084`    | HTTP listen port                                      |
| `CLOUD_PROVIDER`      | `LOCAL`   | Selects KMS adapter (`LOCAL` / `AWS` / `AZURE`)       |
| `AWS_KMS_KEY_ID`      | —         | KMS CMK ARN (required on AWS)                         |
| `AZURE_KEY_VAULT_URL` | —         | Key Vault URI (required on Azure)                     |

### docker compose `.env` file

```bash
# Copy from .env.example
POSTGRES_PASSWORD=auroraforge_dev_secret
REDIS_PASSWORD=redis_dev_secret
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin_secret
VAULT_DEV_ROOT_TOKEN=auroraforge-dev
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=admin
```

---

## 13. API Reference

### Auth Service (:8085)

| Method | Path                     | Auth   | Description                               |
|--------|--------------------------|--------|-------------------------------------------|
| POST   | `/auth/token`            | None   | Issue access + refresh tokens             |
| POST   | `/auth/refresh`          | None   | Exchange refresh token for new pair       |
| POST   | `/auth/logout`           | Bearer | Blacklist JWT in Redis                    |
| GET    | `/.well-known/jwks.json` | None   | RS256 public key in JWKS format           |

### Ingestion Service (:8081)

| Method | Path                                 | Role          | Description                  |
|--------|--------------------------------------|---------------|------------------------------|
| POST   | `/api/v1/tenants/{tid}/events`       | DATA_INGEST   | Ingest a single domain event |
| GET    | `/api/v1/tenants/{tid}/events/{id}`  | DATA_QUERY    | Retrieve a specific event    |
| GET    | `/api/v1/tenants/{tid}/events`       | DATA_QUERY    | List events (paginated)      |

### Sync / DR Service (:8083)

| Method | Path                                   | Role          | Description                              |
|--------|----------------------------------------|---------------|------------------------------------------|
| GET    | `/api/v1/dr/status`                    | PLATFORM_OPS  | DR state, cloud health, replication lag  |
| POST   | `/api/v1/dr/failover/{targetCloud}`    | PLATFORM_OPS  | Manual failover (202 Accepted / 409)     |
| POST   | `/api/v1/dr/recover`                   | PLATFORM_OPS  | Complete recovery → HEALTHY              |
| GET    | `/api/v1/dr/consistency/{tenantId}`    | PLATFORM_OPS  | SHA-256 consistency check (200 / 207)    |
| GET    | `/api/v1/dr/replication-lag`           | PLATFORM_OPS  | Replication lag per cloud in seconds     |
| GET    | `/api/v1/dr/dlq/{tenantId}`           | PLATFORM_OPS  | Paginated DLQ records                    |
| POST   | `/api/v1/dr/dlq/{tenantId}/resolve`   | PLATFORM_OPS  | Bulk resolve DLQ records                 |

### Key Management Service (:8084)

| Method | Path                          | Role        | Description                        |
|--------|-------------------------------|-------------|------------------------------------|
| POST   | `/api/v1/keys/rotate/{class}` | KEY_MANAGER | Trigger key rotation by class      |
| GET    | `/api/v1/keys/{keyId}`        | KEY_MANAGER | Get key metadata                   |
| GET    | `/api/v1/keys`                | KEY_MANAGER | List active keys by classification |

---

## 14. Infrastructure Provisioning

### AWS — bootstrap remote state (one-time)

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

aws s3 mb s3://auroraforge-tfstate-${ACCOUNT_ID} --region us-east-1
aws s3api put-bucket-versioning \
  --bucket auroraforge-tfstate-${ACCOUNT_ID} \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption \
  --bucket auroraforge-tfstate-${ACCOUNT_ID} \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

aws dynamodb create-table \
  --table-name auroraforge-tf-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

### Azure — bootstrap remote state (one-time)

```bash
az login
az account set --subscription <subscription-id>

az group create --name auroraforge-tfstate-rg --location eastus

az storage account create \
  --name auroraforgetfstate \
  --resource-group auroraforge-tfstate-rg \
  --sku Standard_LRS \
  --encryption-services blob \
  --min-tls-version TLS1_2

az storage container create \
  --name tfstate \
  --account-name auroraforgetfstate \
  --public-access off
```

---

## 15. Cloud Deployment – AWS

### Step 1 — Provision infrastructure

```bash
cd infrastructure/terraform/aws

cp terraform.tfvars.example terraform.tfvars
# Required values:
#   aws_region       = "us-east-1"
#   cluster_name     = "auroraforge-eks"
#   rds_instance     = "db.r6g.large"

terraform init \
  -backend-config="bucket=auroraforge-tfstate-${ACCOUNT_ID}" \
  -backend-config="key=aws/terraform.tfstate" \
  -backend-config="region=us-east-1" \
  -backend-config="dynamodb_table=auroraforge-tf-locks"

terraform plan -out=tfplan
terraform apply tfplan

# Resources created:
#   VPC (3 AZs, private + public subnets, NAT gateways)
#   EKS 1.29, managed node groups (on-demand + spot mix)
#   Amazon MSK (Kafka 3.7, 3-broker, EBS encrypted)
#   RDS PostgreSQL 16 Multi-AZ (encrypted, automated backups 7 days)
#   ElastiCache Redis 7 Sentinel (encrypted in-transit and at-rest)
#   KMS CMKs (one per data classification: PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED)
#   S3 buckets (versioned, SSE-KMS, lifecycle to Glacier after 90 days)
#   ECR repositories for all service images
#   IAM roles with IRSA (per-service least-privilege)
#   Route53 hosted zone + ACM certificates
#   Application Load Balancer + WAF v2 rules
```

### Step 2 — Configure kubectl and install cluster tooling

```bash
aws eks update-kubeconfig \
  --region us-east-1 \
  --name auroraforge-eks \
  --alias auroraforge-aws

# Cert-manager
helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace --set installCRDs=true

# External Secrets Operator (AWS Secrets Manager backend)
helm upgrade --install external-secrets external-secrets/external-secrets \
  --namespace external-secrets-system --create-namespace

# AWS Load Balancer Controller
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --namespace kube-system \
  --set clusterName=auroraforge-eks \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=\
arn:aws:iam::${ACCOUNT_ID}:role/aws-lbc

# Crossplane
helm repo add crossplane-stable https://charts.crossplane.io/stable
helm upgrade --install crossplane crossplane-stable/crossplane \
  --namespace crossplane-system --create-namespace \
  -f infrastructure/crossplane/install/crossplane-values.yaml
```

### Step 3 — Apply Crossplane resources

```bash
kubectl apply -f infrastructure/crossplane/providers/aws/
kubectl wait --for=condition=Healthy provider/provider-aws --timeout=120s

kubectl apply -f infrastructure/crossplane/xrd/
kubectl apply -f infrastructure/crossplane/compositions/aws/
kubectl apply -f infrastructure/crossplane/claims/
```

### Step 4 — Push images and deploy

```bash
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS \
  --password-stdin ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com

VERSION=$(git rev-parse --short HEAD)
for SVC in auth ingestion processing sync keymgmt; do
  IMAGE="${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/auroraforge-${SVC}:${VERSION}"
  docker tag auroraforge/auroraforge-${SVC}:1.0.0-SNAPSHOT ${IMAGE}
  docker push ${IMAGE}
done

cd k8s/overlays/aws
kustomize edit set image "*=:${VERSION}"
kubectl apply -k .

kubectl rollout status deployment -n auroraforge --timeout=5m
```

---

## 16. Cloud Deployment – Azure

### Step 1 — Provision infrastructure

```bash
cd infrastructure/terraform/azure

cp terraform.tfvars.example terraform.tfvars
# Required values:
#   location       = "eastus"
#   cluster_name   = "auroraforge-aks"
#   cosmos_regions = ["eastus", "westus2"]

terraform init \
  -backend-config="resource_group_name=auroraforge-tfstate-rg" \
  -backend-config="storage_account_name=auroraforgetfstate" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=azure/terraform.tfstate"

terraform plan -out=tfplan
terraform apply tfplan

# Resources created:
#   VNet (3 AZs) + private DNS zones + NSGs
#   AKS 1.29, system + user node pools (spot node pool for processing)
#   Cosmos DB (multi-region, multi-write, autoscale, continuous backup)
#   Azure Database for PostgreSQL Flexible Server (encrypted, PITR 7 days)
#   Azure Event Hubs Premium (Kafka-compatible protocol)
#   Key Vaults per data classification (RBAC-enabled, purge protection)
#   Azure Blob Storage (LRS + lifecycle management + versioning)
#   Azure Cache for Redis P1 (TLS 1.2 only)
#   Azure Container Registry (geo-replicated)
#   User-assigned Managed Identities per service (Workload Identity)
#   Application Gateway v2 + WAF policy (OWASP 3.2)
```

### Step 2 — Configure kubectl and install cluster tooling

```bash
az aks get-credentials \
  --resource-group auroraforge-rg \
  --name auroraforge-aks \
  --context auroraforge-azure

helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace --set installCRDs=true

helm upgrade --install external-secrets external-secrets/external-secrets \
  --namespace external-secrets-system --create-namespace

helm upgrade --install crossplane crossplane-stable/crossplane \
  --namespace crossplane-system --create-namespace \
  -f infrastructure/crossplane/install/crossplane-values.yaml

kubectl apply -f infrastructure/crossplane/providers/azure/
kubectl wait --for=condition=Healthy provider/provider-azure --timeout=120s

kubectl apply -f infrastructure/crossplane/xrd/
kubectl apply -f infrastructure/crossplane/compositions/azure/
kubectl apply -f infrastructure/crossplane/claims/
```

### Step 3 — Push images and deploy

```bash
az acr login --name auroraforgeacr

VERSION=$(git rev-parse --short HEAD)
for SVC in auth ingestion processing sync keymgmt; do
  IMAGE="auroraforgeacr.azurecr.io/auroraforge-${SVC}:${VERSION}"
  docker tag auroraforge/auroraforge-${SVC}:1.0.0-SNAPSHOT ${IMAGE}
  docker push ${IMAGE}
done

cd k8s/overlays/azure
kustomize edit set image "*=:${VERSION}"
kubectl apply -k .

kubectl rollout status deployment -n auroraforge --timeout=5m
```

---

## 17. Crossplane Cloud-Agnostic Layer

```
Application declares a Claim (e.g. ObjectStorage "auroraforge-raw")
            │
            ▼
CompositeResourceDefinition (XRD) — defines the schema/contract
  spec fields: storageClass, versioning, retentionDays, encryptionClass
  location: infrastructure/crossplane/xrd/
            │
            ▼  matched by cloud label on the Claim
Composition (AWS or Azure)
  AWS:   S3Bucket + BucketVersioning + LifecycleConfiguration + BucketEncryption
  Azure: BlobStorageContainer + ManagementPolicy + EncryptionScope
  location: infrastructure/crossplane/compositions/{aws,azure}/
            │
            ▼
Managed Resources (cloud-specific CRs reconciled by the provider)
  aws:   s3.aws.crossplane.io/Bucket
  azure: storage.azure.crossplane.io/Container
```

**Available XRDs:**

| XRD Name         | Claim Kind      | AWS Resource                  | Azure Resource                   |
|------------------|-----------------|-------------------------------|----------------------------------|
| `XObjectStorage` | `ObjectStorage` | S3 Bucket + policies          | Blob Container + lifecycle       |
| `XRelationalDB`  | `RelationalDB`  | RDS PostgreSQL Multi-AZ       | Azure PG Flexible Server         |
| `XCacheCluster`  | `CacheCluster`  | ElastiCache Redis Sentinel    | Azure Cache for Redis            |
| `XKeyVault`      | `KeyVault`      | KMS CMK + key policy          | Key Vault key + RBAC assignment  |
| `XStreamingBus`  | `StreamingBus`  | MSK topic + ACLs              | Event Hubs topic + consumer group|

---

## 18. Kubernetes Deployment

### Manifests structure

```
k8s/
├── base/
│   ├── namespace.yaml              ← auroraforge namespace
│   ├── configmaps/                 ← non-secret application config
│   ├── deployments/                ← one Deployment per service
│   ├── services/                   ← ClusterIP + separate actuator service
│   ├── hpa/                        ← HorizontalPodAutoscaler (CPU 60% target)
│   ├── pdb/                        ← PodDisruptionBudget (minAvailable=2)
│   └── ingress/                    ← Ingress (cloud annotations in overlays)
└── overlays/
    ├── aws/                        ← EKS annotations, ALB ingress, IRSA service accounts
    └── azure/                      ← AKS annotations, AGIC ingress, Workload Identity
```

### Resource quotas

| Service     | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-------------|-------------|-----------|----------------|--------------|
| auth        | 250m        | 1         | 512Mi          | 1Gi          |
| ingestion   | 500m        | 2         | 1Gi            | 2Gi          |
| processing  | 1           | 4         | 2Gi            | 4Gi          |
| sync        | 500m        | 2         | 1Gi            | 2Gi          |
| keymgmt     | 250m        | 1         | 512Mi          | 1Gi          |

### Kubernetes probe configuration

```yaml
# Example: ingestion service (actuator port 8091)
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8091
  initialDelaySeconds: 60
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8091
  initialDelaySeconds: 20
  periodSeconds: 5
  failureThreshold: 3
```

### Secret injection

Secrets are never stored in environment variables or plain Kubernetes Secrets:

```
AWS:   Secrets Manager → ExternalSecret CR → Kubernetes Secret → projected volume
Azure: Key Vault        → ExternalSecret CR → Kubernetes Secret → projected volume
Local: Vault Agent sidecar → /vault/secrets/ (file-based, auto-renewed)
```

Services consume secrets as files via:
`--spring.config.additional-location=file:/vault/secrets/`

---

## 19. Resilience Configuration

### Circuit breaker instances

| Instance          | Failure Rate | Wait in Open | Sliding Window | Half-Open Calls |
|-------------------|-------------|--------------|----------------|-----------------|
| `s3`              | 50%         | 30 s         | 20             | 3               |
| `azureBlob`       | 50%         | 30 s         | 20             | 3               |
| `kafka-publisher` | 50%         | 10 s         | 20             | —               |
| `key-management`  | 20%         | 60 s         | 10             | —               |
| `aws-sync`        | 50%         | 30 s         | 20             | 3               |
| `azure-sync`      | 50%         | 30 s         | 20             | 3               |

All circuit breakers register a health indicator visible at `/actuator/health`.
`CircuitBreakerEventLogger` emits a structured WARN log on every state transition.

### Bulkhead instances (thread pool isolation)

| Instance     | Max Concurrent Calls | Max Wait |
|--------------|----------------------|----------|
| `s3`         | 20                   | 0 ms (fail-fast) |
| `azureBlob`  | 20                   | 0 ms             |
| `aws-sync`   | 20                   | 0 ms             |
| `azure-sync` | 20                   | 0 ms             |

Bulkhead exhaustion throws `CloudStorageUnavailableException`, which the caller catches to route the payload to the DLQ.

### DLQ retry policy

| Parameter           | Value                                         |
|---------------------|-----------------------------------------------|
| Max retry attempts  | 5                                             |
| Base backoff        | 30 s                                          |
| Formula             | `30 × 2^retryCount`, capped at 3 600 s        |
| Scheduler interval  | Every 60 s                                    |
| Final status        | `EXHAUSTED` — metric counter incremented, alert fires |

---

## 20. Security Hardening

### Response headers

| Header                        | Value                                                         |
|-------------------------------|---------------------------------------------------------------|
| `Strict-Transport-Security`   | `max-age=31536000; includeSubDomains; preload`                |
| `Content-Security-Policy`     | `default-src 'self'; script-src 'self'; frame-ancestors 'none'; …` |
| `X-Frame-Options`             | `DENY`                                                        |
| `X-Content-Type-Options`      | `nosniff`                                                     |
| `Referrer-Policy`             | `no-referrer`                                                 |
| `Permissions-Policy`          | `camera=(), microphone=(), geolocation=(), payment=()`        |
| `Cache-Control`               | `no-store, no-cache, must-revalidate`                         |

Both `SecurityHeadersFilter` (servlet filter) and Spring Security's `.headers()` DSL set overlapping headers so they apply in both production (full filter chain) and MockMvc integration tests.

### Declarative audit logging

Annotate any Spring bean method with `@AuditLog` for zero-boilerplate audit emission:

```java
@AuditLog(
    eventType = AuditEventType.KEY_ROTATION_COMPLETED,
    resource  = "KEY",
    action    = "Rotated encryption key"
)
public void rotateKey(String tenantId, String keyId) { ... }
```

`AuditLogAspect` intercepts the method, resolves the actor from `SecurityContext` and `tenantId` from the parameter named `tenantId`, and writes to the dedicated `AUDIT` logger → `audit.log` (90-day retention, never merged with operational logs).

### Network security checklist

- mTLS enforced between all services within the mesh
- No plain HTTP accepted in production (HSTS preload + ingress TLS termination at LB)
- Kafka uses SASL/SCRAM-SHA-512 + per-topic ACLs
- PostgreSQL replication slots accessible only from the Debezium service account
- S3 bucket policy independently denies PutObject without SSE-KMS (defence-in-depth)
- IAM: per-service `ServiceAccount` with IRSA (AWS) / Workload Identity (Azure) — no shared credentials

---

## 21. Observability Runbook

### Check DR state and replication lag

```bash
# From DR REST API
curl -s -H "Authorization: Bearer $OPS_TOKEN" \
  http://localhost:8083/api/v1/dr/status | jq '{state, activeCloud, replicationLagSeconds}'

# From Prometheus
curl -sG http://localhost:9090/api/v1/query \
  --data-urlencode 'query=auroraforge_replication_lag_seconds' | jq .data.result
```

### Tail structured logs

```bash
# Local dev (colored console)
docker logs -f auroraforge-ingestion

# Kubernetes (JSON mode in prod) — extract key fields
kubectl logs -n auroraforge -l app=auroraforge-sync -f \
  | jq '{ts: .["@timestamp"], lvl: .level, msg: .message, req: .requestId, tenant: .tenantId}'

# Audit log only
kubectl exec -n auroraforge deploy/auroraforge-keymgmt \
  -- tail -f /logs/audit.log | jq .
```

### Correlate a request across all services

Every request carries `X-Request-ID` through HTTP headers and Kafka record headers.
Find it in Jaeger or any log aggregator:

```bash
# Grafana Loki query
{namespace="auroraforge"} | json | requestId="<uuid>"

# Jaeger REST API
curl "http://localhost:16686/api/traces?service=auroraforge-ingestion&tags=%7B%22requestId%22%3A%22<uuid>%22%7D"
```

### Alert thresholds (Prometheus rules)

| Alert                          | Condition                                     | Severity |
|--------------------------------|-----------------------------------------------|----------|
| `AuroraForgeDRNotHealthy`      | `auroraforge_dr_state > 0` for 2 min          | critical |
| `AuroraForgeReplicationLagHigh`| `replication_lag_seconds > 60`                | warning  |
| `AuroraForgeRPOBreach`         | `replication_lag_seconds > 300`               | critical |
| `AuroraForgeDLQExhausted`      | `dlq_exhausted_total > 0`                     | warning  |
| `CircuitBreakerOpen`           | CB emits `circuit-breaker-open` log event     | warning  |
| `SlowRequests`                 | p99 latency > 2 s over 5-min window           | warning  |

---

## 22. Disaster Recovery Runbook

### Trigger manual failover to Azure

```bash
curl -X POST \
  -H "Authorization: Bearer $OPS_TOKEN" \
  http://localhost:8083/api/v1/dr/failover/azure
# Returns 202 Accepted
# State machine: HEALTHY → FAILOVER_INITIATED → FAILOVER_ACTIVE
# (second POST while FAILOVER_ACTIVE is a 409 no-op — idempotent)

# Monitor transition
watch -n 5 'curl -s -H "Authorization: Bearer $OPS_TOKEN" \
  http://localhost:8083/api/v1/dr/status | jq "{state, activeCloud}"'
```

### Verify data consistency before or after failover

```bash
curl -s -H "Authorization: Bearer $OPS_TOKEN" \
  "http://localhost:8083/api/v1/dr/consistency/t-acme?aggregateIds=agg-1,agg-2,agg-3" \
  | jq '{consistent: .isFullyConsistent, pct: .consistencyPercent, diverged: .divergedAggregateIds}'
# 200 = fully consistent; 207 = divergences detected (ids listed)
```

### Review and resolve DLQ

```bash
# Inspect PENDING_RETRY and CONFLICT_REVIEW records
curl -s -H "Authorization: Bearer $OPS_TOKEN" \
  "http://localhost:8083/api/v1/dr/dlq/t-acme?page=0&size=20" | jq .

# Bulk-resolve after manual review
curl -X POST \
  -H "Authorization: Bearer $OPS_TOKEN" \
  -H 'Content-Type: application/json' \
  "http://localhost:8083/api/v1/dr/dlq/t-acme/resolve" \
  -d '{"recordIds":["<uuid-1>","<uuid-2>"]}'
```

### Complete recovery (return primary to AWS)

```bash
# Call only after: AWS is healthy AND DLQ has drained (dlq_exhausted_total == 0)
curl -X POST \
  -H "Authorization: Bearer $OPS_TOKEN" \
  http://localhost:8083/api/v1/dr/recover
# 200 OK → state = HEALTHY, activeCloud = aws
# 409 Conflict if not in RECOVERING state
```

### SLA targets

| Scenario               | RTO     | RPO    | Mechanism                                            |
|------------------------|---------|--------|------------------------------------------------------|
| AZ failure             | < 30 s  | 0      | EKS/AKS multi-AZ + RDS/Cosmos auto failover          |
| AWS region failure     | < 5 min | ~1 s   | Cosmos DB active-active write + Route53 health checks|
| Azure region failure   | < 5 min | ~1 s   | Cosmos DB automatic failover priority                |
| Full cloud failure     | < 15 min| < 30 s | Global load balancer routes to surviving cloud       |
| Data corruption        | < 1 h   | < 5 min| S3 versioning + Cosmos DB continuous backup (PITR)   |

---

## 23. Contributing

### Branch and commit conventions

```
Branch:   feat/<module>/<short-description>
          fix/<module>/<short-description>
          chore/<description>

Commit:   <type>(<scope>): <imperative description>
Types:    feat | fix | refactor | test | docs | chore | perf
Example:  feat(sync): add FIELD_MERGE conflict resolution strategy
```

### PR checklist

- [ ] `mvn -f services/pom.xml test` passes
- [ ] `mvn -f services/pom.xml verify -Pfailsafe` passes (requires `docker compose up -d`)
- [ ] ArchUnit architecture tests pass (`*ArchTest`)
- [ ] New external calls decorated with `@CircuitBreaker`, `@Bulkhead`, and `@Retry`
- [ ] New endpoints added to the route matrix in `AuroraForgeSecurityConfig`
- [ ] Sensitive fields absent from logs (body sanitization covers `password`, `token`, `secret`)
- [ ] Structured log event emitted for any new audit-worthy action (`@AuditLog`)
- [ ] PR ≤ 400 lines changed (split larger changes)

### Running the test suite

```bash
# Unit tests — fast, no Docker
mvn -f services/pom.xml test

# Unit + integration tests — requires docker compose up -d
mvn -f services/pom.xml verify -Pfailsafe

# Specific module
mvn -f services/auroraforge-sync/pom.xml test

# Architecture fitness functions
mvn -f services/pom.xml test -Dtest="*ArchTest"
```
