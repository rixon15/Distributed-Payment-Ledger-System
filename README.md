### Distributed Payment & Ledger System

A high-fidelity simulation of a financial payment processing system designed to handle concurrency, idempotency, and distributed consistency.

This project demonstrates how to build a Zero Trust microservices architecture that solves the "Double Spend" problem using Pessimistic Locking and Double-Entry Accounting.

### System Architecture

The system follows an Event-Driven Architecture using Kafka for asynchronous decoupling between the Payment Gateway and the Ledger.

```mermaid
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
```
### Key Features & Design Patterns

1. Zero Trust Security & Token Rotation

    Stateless Validation: Services validate JWT signatures locally using cached JWKS public keys to avoid network bottlenecks.

    Theft Detection: Implements Refresh Token Rotation with "Token Family" tracking. If a rotated token is reused, the entire family is revoked, locking out potential attackers.

2. Financial Integrity

    Optimistic Concurrency Control: The Ledger Service uses a versioning mechanism (e.g., version column or eTag) to manage updates. Transactions verify that the record has not been modified by another process since it was read. If a version mismatch occurs, the transaction is rejected and must be retried.

    Conflict Resolution: Instead of physical database locks, the system relies on atomic "Compare-and-Swap" (CAS) operations. This eliminates database-level deadlocks and significantly increases throughput during low-to-medium contention.

    Double-Entry Accounting: Every transaction is recorded as two immutable postings (Debit & Credit) where $\sum \text{Amount} = 0$.

3. Distributed Resiliency

    Idempotency: The Payment Service uses Atomic Redis locks (SETNX) to ensure no request is processed twice, even if the client retries aggressively.

    Non-Blocking Retries: The Notification Service uses a "Retry Topic" and Dead Letter Queue (DLQ) pattern to handle failures without blocking the main consumer group.

### End-to-End Request Flow

This sequence diagram illustrates the lifecycle of a transfer request as it propagates through the system, demonstrating the **Zero Trust** handoff between the Gateway and Services, and the **Event-Driven** settlement phase.

```mermaid
sequenceDiagram
    participant User as User
    participant GW as API Gateway
    participant Auth as Auth Service (IdP)
    participant PG as Payment Service
    participant Redis
    participant Kafka
    participant Ledger as Ledger Service
    participant DB as Postgres (Ledger)
    participant Notif as Notification Service

    Note over User, GW: Authentication Phase
    User->>GW: POST /api/v1/transfer (Bearer <JWT>)
    activate GW
    opt Public Key Not Cached
        GW->>Auth: GET /.well-known/jwks.json
        Auth-->>GW: Return Public Keys
    end

    GW->>GW: Validate JWT Signature (Local)
    alt Token Invalid
        GW-->>User: 401 Unauthorized
    else Token Valid
        GW->>PG: Forward Request (User-ID in Header)
        activate PG
        
        Note over PG, Redis: Idempotency (Atomic)
        PG->>Redis: SETNX key:req_id "processing"
        
        alt Key Exists
            PG-->>GW: 409 Conflict
            GW-->>User: 409 Conflict
        else Lock Acquired
            PG->>Kafka: Publish: transaction.initiated
            PG-->>GW: 202 Accepted
        
            GW-->>User: 202 Accepted (Processing)
        end
        deactivate PG
    end
    deactivate GW

    Note over Kafka, Ledger: Async Settlement Phase
    Kafka->>Ledger: Consume: transaction.initiated
    activate Ledger
    Ledger->>DB: Begin Transaction
    Ledger->>DB: Check Funds & Lock Row
    alt Insufficient Funds
        Ledger->>DB: Rollback
        Ledger->>Kafka: Publish: transaction.failed
    else Sufficient Funds
        Ledger->>DB: Insert Debit/Credit
        Ledger->>DB: Commit
        Ledger->>Kafka: Publish: transaction.posted
    end
    deactivate Ledger

    Note over Kafka, Notif: Notification Phase
    par Handle Success
        Kafka->>Notif: Consume: transaction.posted
        Notif->>User: Email "Money Sent!"
    and Handle Failure
        Kafka->>Notif: Consume: transaction.failed
        Notif->>User: Email "Transfer Failed"
    end
```

### Microservices Breakdown:

### Authentication Service

Handles identity, JWT issuance, and secure token rotation.

    Pattern: OAuth2 Resource Server / Refresh Token Rotation.

    Store: Redis (for white-listing refresh tokens).

```mermaid
sequenceDiagram
    participant User
    participant Attacker
    participant API as Auth Service
    participant Redis

    Note over User, Redis: 1. Legitimate Rotation
    User->>API: Refresh(Token A)
    API->>Redis: Check Token A
    Redis-->>API: Valid (It is "Latest")
    API->>Redis: Set "Latest" = Token B<br/>Add A to "Used Set"
    API-->>User: Return Token B

    Note over User, Redis: 2. Theft Attempt (Reuse)
    Attacker->>API: Refresh(Token A)
    API->>Redis: Check Token A
    
    alt Is Latest?
        Redis-->>API: No (Latest is B)
    else Is in Used Set?
        Redis-->>API: YES! (Token A was already used)
        
        Note right of API: THEFT DETECTED
        
        API->>Redis: DEL "Latest" (Revoke Token B)
        API->>Redis: DEL "Used Set"
        
        API-->>Attacker: 403 Forbidden (Account Locked)
    end

    Note over User, Redis: 3. Collateral Damage
    User->>API: Refresh(Token B)
    API->>Redis: Check Token B
    Redis-->>API: Null (Key deleted)
    API-->>User: 403 Forbidden (Please Login Again)
```

### Payment Service (The Orchestrator)

Handles the "Authorization" phase. It talks to the external world (Bank Simulator) and ensures the request is valid before passing it to the Ledger.

    Pattern: Saga (Orchestration) / Idempotency Key.

    Safety: Uses SETNX in Redis to lock the request ID immediately.


```mermaid 
    sequenceDiagram
    title Internal Payment Service Logic (No Stripe)
    participant API as Controller
    participant Risk as Risk Engine (Simple)
    participant Bank as Bank Simulator (Stub)
    participant Redis
    participant Kafka

    API->>Redis: 1. Check Idempotency Key
    alt Key Found
        API-->>User: 409 Conflict
    else New Request
        
        Note over API, Bank: "Authorization" Phase
        API->>Risk: 2. Check Rules (e.g. Max $5000)
        
        alt Risk High
            API-->>User: 403 Forbidden (Risk Rejected)
        else Risk OK
            API->>Bank: 3. Request Funds (Simulate 2s delay)
            
            alt Bank Fails (Random)
                Bank-->>API: Error (Insufficient Funds)
                API-->>User: 422 Unprocessable Entity
            else Bank Success
                Bank-->>API: Auth Code: ABC-123
                
                Note over API, Kafka: "Clearing" Phase
                API->>Redis: 4. Set Idempotency Key (TTL 24h)
                API->>Kafka: 5. Publish 'transaction.authorized'
                API-->>User: 202 Accepted
            end
        end
    end
```

### Ledger Service (The Source of Truth)

The heart of the system. It consumes events and updates balances.

    Pattern: Event Sourcing (Lite) / Pessimistic Locking.

    Schema: Implements a strict Double-Entry schema.

Table	Purpose

ACCOUNTS	Snapshot of current balance (Locked for writes).

TRANSACTIONS	Immutable journal of what happened.

POSTINGS	Immutable record of math (Debit/Credit pairs).

```mermaid
    erDiagram
    %% Snapshot Table
    ACCOUNTS {
        uuid id PK
        uuid user_id
        string name
        sting type
        decimal balance "Updated inside the lock"
        string currency
        int version
        timestamp updated_at
    }

    %% Immutable Journal
    TRANSACTIONS {
        uuid id PK 
        string reference_id "Idempotency Key"
        string type
        string description
        timestamp created_at
        timestamp effective_date
        string metadata
        string status
    }

    %% Immutable Ledger Entries
    POSTINGS {
        uuid id PK
        uuid transaction_id FK
        uuid account_id FK
        decimal amount
        string direction
    }

    ACCOUNTS ||--o{ POSTINGS : "has history of"
    TRANSACTIONS ||--|{ POSTINGS : "consists of"
```
```mermaid
    sequenceDiagram
    title Ledger Service: Optimistic Concurrency (CAS)
    participant Kafka
    participant Service as Ledger Worker
    participant DB as Postgres

    Kafka->>Service: Consume: transfer.initiated (From: A, To: B, ID: 123)
    activate Service
    
    loop Retry Strategy (e.g., Max 3 attempts)
        Note over Service, DB: DB Transaction Starts (BEGIN)
        
        Note right of Service: 1. Idempotency Check
        Service->>DB: INSERT INTO handled_events (event_id) VALUES (123)
        
        alt Insert Fails (Duplicate)
            DB-->>Service: Error: Unique Constraint Violation
            Service->>DB: ROLLBACK
            Service->>Kafka: Ack (Ignore Duplicate)
            Note right of Service: Break Loop
        else Insert Success
            
            Note right of Service: 2. Snapshot Read (No Locks)
            Service->>DB: SELECT balance, version FROM accounts WHERE id IN (A, B)
            DB-->>Service: Return: {A: $100, v1}, {B: $50, v5}
            
            Service->>Service: Check Balance (A >= Amount)
            
            alt Sufficient Funds
                Service->>DB: INSERT into postings (Debit A, Credit B)...
                
                Note right of Service: 3. Conditional Update (CAS)
                Service->>DB: UPDATE accounts SET bal=A-Amt, ver=v1+1 WHERE id=A AND ver=v1
                Service->>DB: UPDATE accounts SET bal=B+Amt, ver=v5+1 WHERE id=B AND ver=v5
                
                alt Any Update Returns 0 Rows
                    Note right of Service: Version Mismatch (Conflict)
                    Service->>DB: ROLLBACK
                    Service->>Service: Sleep (Jitter) & Continue Loop
                else Both Updates Return 1 Row
                    Service->>DB: COMMIT
                    Service->>Kafka: Publish: transaction.posted
                    Note right of Service: Break Loop (Success)
                end

            else Insufficient Funds
                Service->>DB: ROLLBACK
                Service->>Kafka: Publish: transaction.failed
                Note right of Service: Break Loop
            end
        end
    end
    deactivate Service
```

### Notification Service

Listens for transaction.posted or transaction.failed events to alert the user.

    Pattern: Rate Limiting / Dead Letter Queue.

    Tech: Thymeleaf (Template Rendering), SendGrid (Stub).

```mermaid
    flowchart TD
    Start((Kafka Event:<br/>transaction.posted)) --> CheckDup{Idempotency Check<br/>Already Processed?}
    
    CheckDup -- Yes --> Ignore((Ignore))
    CheckDup -- No --> Prefs{Check User<br/>Preferences}

    Prefs -- "Email" --> RenderEmail[Render HTML Template<br/>]
    Prefs -- "SMS" --> RenderSMS[Construct SMS]
    
    RenderEmail --> RateLimit{Rate Limiter<br/>Bucket Check}
    
    RateLimit -- "Too Fast" --> RetryQ
    RateLimit -- "OK" --> SendGW[Call SendGrid API]

    SendGW -- 200 OK --> Log[Log Success &<br/>Mark Processed]
    
    SendGW -- 500/Timeout --> RetryLogic{Retry Count < 3?}
    
    RetryLogic -- Yes --> RetryQ[Publish to<br/>notification.retry]
    RetryLogic -- No --> DLQ[Publish to<br/>Dead Letter Queue]
    
    RetryQ -.->|Delayed Consumer<br/>| Start
```

### Technology Stack

    Core: Java 25, Spring Boot 4.0

    Data: PostgreSQL, Redis

    Messaging: Apache Kafka

    Infrastructure: Docker, Docker Compose

    Testing: JUnit 5, Testcontainers

### How to Run

    Clone the Repo
    Bash

    git clone https://github.com/yourusername/payment-simulation.git
    cd payment-simulation

    Start Infrastructure Spin up Postgres, Redis, Kafka, and Zookeeper.
    Bash

    docker-compose up -d

    Run Services You can run each microservice via Maven or as Docker containers.
    Bash

    ./mvnw spring-boot:run

### Future Improvements

    Implement mTLS with a Service Mesh (Istio) for internal traffic encryption.

    Add Prometheus/Grafana dashboards for real-time monitoring of transaction throughput.

### Master System Architecture


```mermaid
    flowchart TB
 subgraph subGraph0["Public Internet"]
        User["Angular Frontend"]
        ExternalBank["External Bank Simulator<br>"]
        EmailProvider["SendGrid / Twilio"]
  end
 subgraph subGraph1["Auth Service Domain"]
        Auth["Auth Service<br>"]
        AuthDB[("Postgres: Users")]
        AuthRedis[("Redis: Refresh Tokens")]
  end
 subgraph subGraph2["Payment Service Domain"]
        Payment["Payment Service<br>"]
        PayRedis[("Redis: Idempotency Locks")]
  end
 subgraph subGraph3["Ledger Service Domain"]
        Ledger["Ledger Service<br>"]
        LedgerDB[("Postgres: Accounting")]
  end
 subgraph subGraph4["Notification Service Domain"]
        Notif["Notification Service"]
        DLQ["Dead Letter Queue"]
  end
 subgraph subGraph5["Private Cluster (VPC / Kubernetes)"]
    direction TB
        GW["API Gateway<br>"]
        GW_Cache[("Redis: JWKS Cache")]
        subGraph1
        subGraph2
        subGraph3
        subGraph4
        Kafka{"Kafka Message Broker"}
  end
    GW -- Validate JWT --> GW_Cache
    Auth --> AuthDB & AuthRedis
    Payment -- Atomic Lock (SETNX) --> PayRedis
    Ledger -- Optimistic Locking --> LedgerDB
    User -- "1. HTTPS / REST" --> GW
    GW -. "2. Login / Refresh" .-> Auth
    GW -- "3. Proxy Request (Bearer Token)" --> Payment
    Payment -- "4. Charge Card" --> ExternalBank
    Payment -- "5. Publish Event: deposit.authorized" --> Kafka
    Kafka -. "6. Consume (Async)" .-> Ledger
    Kafka -. "7. Consume (Async)" .-> Notif
    Notif -- "8. Send Email" --> EmailProvider
    Notif -- Max Retries Exceeded --> DLQ

     ExternalBank:::ext
     EmailProvider:::ext
     AuthDB:::db
     AuthRedis:::db
     PayRedis:::db
     LedgerDB:::db
     GW_Cache:::db
     Kafka:::bus
    classDef service fill:#1f2937,stroke:#3b82f6,stroke-width:2px,color:#fff
    classDef db fill:#1e3a8a,stroke:#60a5fa,stroke-width:2px,color:#fff
    classDef ext fill:#374151,stroke:#f87171,stroke-width:2px,stroke-dasharray: 5 5,color:#fff
    classDef bus fill:#7c2d12,stroke:#fb923c,stroke-width:4px,color:#fff
```
