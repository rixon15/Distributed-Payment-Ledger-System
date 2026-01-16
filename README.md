Distributed Payment & Ledger System

A high-fidelity simulation of a financial payment processing system designed to handle concurrency, idempotency, and distributed consistency.

This project demonstrates how to build a Zero Trust microservices architecture that solves the "Double Spend" problem using Pessimistic Locking and Double-Entry Accounting.
🏗 System Architecture

The system follows an Event-Driven Architecture using Kafka for asynchronous decoupling between the Payment Gateway and the Ledger.

flowchart TB
    subgraph "Public Internet"
        User[Angular Frontend]
        ExternalBank[External Bank Stub]
        EmailProvider[SendGrid]
    end

    subgraph "Private Cluster"
        GW[API Gateway]
        Auth[Auth Service]
        Payment[Payment Service]
        Ledger[Ledger Service]
        Notif[Notification Service]
        Kafka{Kafka Broker}
        
        GW --> Payment
        Payment -- "Async Event" --> Kafka
        Kafka --> Ledger
        Kafka --> Notif
    end
    
    User --> GW
    GW -.-> Auth

(See the full "God View" diagram below for deep technical details)
Key Features & Design Patterns
1. Zero Trust Security & Token Rotation

    Stateless Validation: Services validate JWT signatures locally using cached JWKS public keys to avoid network bottlenecks.

    Theft Detection: Implements Refresh Token Rotation with "Token Family" tracking. If a rotated token is reused, the entire family is revoked, locking out potential attackers.

2. Financial Integrity (The "Double Spend" Fix)

    Pessimistic Locking: The Ledger Service uses SELECT ... FOR UPDATE to physically lock database rows during a transaction, preventing race conditions.

    Deadlock Prevention: Locks are always acquired in a deterministic order (lowest UUID first) to prevent database deadlocks.

    Double-Entry Accounting: Every transaction is recorded as two immutable postings (Debit & Credit) where ∑Amount=0.

3. Distributed Resiliency

    Idempotency: The Payment Service uses Atomic Redis locks (SETNX) to ensure no request is processed twice, even if the client retries aggressively.

    Non-Blocking Retries: The Notification Service uses a "Retry Topic" and Dead Letter Queue (DLQ) pattern to handle failures without blocking the main consumer group.

Microservices Breakdown
Authentication Service

Handles identity, JWT issuance, and secure token rotation.

    Pattern: OAuth2 Resource Server / Refresh Token Rotation.

    Store: Redis (for white-listing refresh tokens).

sequenceDiagram
    participant User
    participant API as Auth Service
    participant Redis

    Note over User, Redis: Theft Detection Logic
    User->>API: Refresh(Token A)
    API->>Redis: Check Token A
    
    alt Is Token Reused?
        Redis-->>API: YES (Stolen!)
        API->>Redis: Revoke Entire Token Family
        API-->>User: 403 Forbidden (Account Locked)
    else Valid Rotation
        API->>Redis: Issue Token B
        API-->>User: 200 OK
    end

Payment Service (The Orchestrator)

Handles the "Authorization" phase. It talks to the external world (Bank Simulator) and ensures the request is valid before passing it to the Ledger.

    Pattern: Saga (Orchestration) / Idempotency Key.

    Safety: Uses SETNX in Redis to lock the request ID immediately.


sequenceDiagram
    participant API as Payment Service
    participant Redis
    participant Kafka

    API->>Redis: SETNX key:tx_123 "PROCESSING"
    alt Key Exists
        API-->>User: 409 Conflict
    else Lock Acquired
        API->>Bank: Charge Card()
        API->>Kafka: Publish "deposit.authorized"
        API-->>User: 202 Accepted
    end

Ledger Service (The Source of Truth)

The heart of the system. It consumes events and updates balances.

    Pattern: Event Sourcing (Lite) / Pessimistic Locking.

    Schema: Implements a strict Double-Entry schema.

Table	Purpose
ACCOUNTS	Snapshot of current balance (Locked for writes).
TRANSACTIONS	Immutable journal of what happened.
POSTINGS	Immutable record of math (Debit/Credit pairs).
Notification Service

Listens for transaction.posted or transaction.failed events to alert the user.

    Pattern: Rate Limiting / Dead Letter Queue.

    Tech: Thymeleaf (Template Rendering), SendGrid (Stub).

Technology Stack

    Core: Java 25, Spring Boot 4.0

    Data: PostgreSQL, Redis

    Messaging: Apache Kafka

    Infrastructure: Docker, Docker Compose

    Testing: JUnit 5, Testcontainers

How to Run

    Clone the Repo
    Bash

    git clone https://github.com/yourusername/payment-simulation.git
    cd payment-simulation

    Start Infrastructure Spin up Postgres, Redis, Kafka, and Zookeeper.
    Bash

    docker-compose up -d

    Run Services You can run each microservice via Gradle/Maven or as Docker containers.
    Bash

    ./gradlew bootRun

Future Improvements

    Implement mTLS with a Service Mesh (Istio) for internal traffic encryption.

    Add Prometheus/Grafana dashboards for real-time monitoring of transaction throughput.

Master System Architecture


flowchart TB
    subgraph "Cluster"
        GW[API Gateway] -- JWT --> Auth
        GW -- Proxy --> Payment
        
        Payment -- Atomic Lock --> Redis[(Redis)]
        Payment -- Event --> Kafka{Kafka}
        
        Kafka --> Ledger
        Ledger -- Lock Row --> DB[(Postgres)]
        
        Kafka --> Notif
        Notif -- Retry --> DLQ[Dead Letter Queue]
    end****
