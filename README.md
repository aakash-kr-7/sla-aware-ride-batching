# SLA-Aware Ride Batching

A backend system for turning independent, on-demand employee ride requests into shared trips without compromising vehicle capacity, rider detour constraints, or promised pickup times.

This repository is my implementation of the **On-Demand Ride Batching** backend case study.

The project is currently in the design and early implementation stage. I am intentionally documenting the problem and my assumptions before committing to an architecture because most of the difficulty here is not in creating CRUD APIs — it is in deciding when several individually valid ride requests can safely become one shared trip.

## The problem as I understand it

Employees can request rides throughout the day.

Sending one vehicle for every request would be simple, but inefficient. The system should instead collect requests arriving within short time windows and attempt to combine compatible requests into shared, multi-stop trips.

The important part is that batching is not allowed to break the guarantees made to individual riders.

A request therefore cannot simply be grouped with another request because the pickup locations happen to be nearby.

A valid batch also needs to respect:

- serviceable pickup zones;
- duplicate-request protection;
- the batching time window;
- origin and destination compatibility;
- vehicle capacity;
- acceptable rider detour;
- pickup SLA;
- driver availability;
- driver rejection/cool-off behaviour.

The system must also cope with changes after a trip has been created, particularly late ride requests and cancellations.

One requirement I think is especially important is that cancelling a booking should only cause the affected trip to be reconsidered. Other valid trips should remain stable.

That gives me an early design principle for the project:

> **Optimisation should not come at the expense of correctness or stability.**

## What I think the core engineering problem is

At first glance this looks like a ride-booking API.

I don't think that is really the interesting problem.

Creating a booking is relatively straightforward.

The difficult decision is what happens after several pending bookings exist:

```text
Can these requests travel together?

            │
            ├── Are they temporally compatible?
            ├── Are their pickup locations compatible?
            ├── Are they travelling in roughly the same direction?
            ├── Is there enough vehicle capacity?
            ├── Is the additional detour acceptable?
            └── Can every pickup still happen within its SLA?
```

Only if those constraints remain valid should the system create or modify a shared trip.

For that reason I currently think of the project less as a booking application and more as a small **constraint-aware ride orchestration engine**.

## Current design principles

These are not implementation details yet. They are the principles I currently want the implementation to preserve.

### 1. SLA is a hard constraint

Batching is useful only when it does not cause a rider to miss the promised pickup time.

If adding a rider makes the trip violate an existing rider's SLA, that candidate batch should be rejected even if sharing the vehicle would otherwise be more efficient.

### 2. Batching decisions should be explainable

I want the system to be able to explain why a request was or was not placed into a particular trip.

For example:

```text
Candidate trip T-104

Zone compatible        ✓
Direction compatible   ✓
Capacity available     ✓
Pickup SLA             ✗

Decision: reject candidate
Reason: pickup SLA would be exceeded
```

This should make the batching logic easier to debug, test and demonstrate.

### 3. Duplicate prevention must survive concurrency

The case study explicitly mentions double-submission.

A simple application-level `exists()` check followed by `save()` may prevent ordinary duplicate requests but can still fail when two equivalent requests arrive concurrently.

I therefore want duplicate prevention to eventually have a persistence-level guarantee rather than relying only on controller/service logic.

The exact deduplication key still needs to be finalized.

### 4. Changes should have a bounded blast radius

A late request should only join an existing trip if that insertion remains valid.

Similarly, a cancellation should trigger reconsideration of the affected trip rather than reshuffling unrelated trips.

I want trip planning to be local enough that these behaviours remain predictable.

### 5. Infrastructure should earn its place

I expect the final system to include persistence, structured logging, metrics, authentication and probably caching.

However, I don't want to introduce infrastructure purely because it makes the architecture diagram larger.

For example, if caching is introduced, I want to be able to answer:

- what data is being cached;
- why that data is hot enough to justify caching;
- how it is invalidated;
- what stale data would mean for the system.

The same principle applies to every additional component.

## Rough request lifecycle

My current mental model is:

```text
Ride request
     │
     ▼
Input validation
     │
     ▼
Serviceable-zone check
     │
     ▼
Duplicate detection
     │
     ▼
Pending booking
     │
     ▼
Batching window
     │
     ▼
Candidate clustering
     │
     ▼
Capacity / detour / SLA evaluation
     │
     ├── infeasible ──► split / create another trip
     │
     └── feasible
            │
            ▼
        Shared trip
            │
            ▼
     Eligible drivers
            │
            ▼
        Driver offer
```

This will almost certainly change as I start implementing and testing the behaviour.

I would rather record those changes than pretend the first design was perfect.

## Areas I still need to define

Some parts of the case study deliberately leave implementation choices open.

Before implementing the batching algorithm I need to make explicit assumptions around:

- how serviceable zones are represented;
- how location proximity is measured;
- how destination direction compatibility is calculated;
- what counts as an acceptable detour;
- how pickup-time estimates are produced;
- what exactly defines a booking slot for deduplication;
- how vehicle capacity is represented;
- how a driver's proximity to a trip is determined;
- how long driver rejection cool-off should last.

I plan to keep these assumptions in `docs/assumptions.md` rather than hide them inside implementation constants.

## Planned implementation sequence

I plan to build the system incrementally.

The first working slice will be deliberately small:

```text
booking request
      ↓
validation
      ↓
serviceability
      ↓
deduplication
      ↓
persistent PENDING booking
```

Once that behaviour is well tested, I will introduce batching windows and clustering.

After that I will add the constraints that determine whether a proposed shared trip is actually feasible:

```text
capacity
    +
detour
    +
pickup SLA
```

Driver assignment, late insertion and cancellation/replanning will be built after the trip formation rules are reliable.

Production-oriented concerns such as authentication, metrics, caching and failure handling will then be added where the implementation gives them a concrete purpose.

## Testing approach

I don't want the tests to only prove that endpoints return HTTP 200.

The important tests should prove business invariants.

Examples I already expect to need include:

```text
same booking submitted twice creates one booking

concurrent equivalent requests still create one booking

pickup outside serviceable zone is rejected

five compatible requests cannot fit into one four-seat vehicle

candidate trip is rejected when one rider would miss pickup SLA

late booking is rejected when insertion breaks an existing rider's SLA

driver on active break never receives an offer

driver who rejects a trip is not immediately offered the same trip again

cancelling one booking replans its trip without modifying unrelated trips
```

I expect this list to grow as implementation exposes edge cases I have not considered yet.

## Repository notes

The commit history is intentionally part of this project.

I want each meaningful capability or design correction to appear as its own change rather than implementing the entire system locally and pushing one final snapshot.

That should also make it possible to see how the solution changed as I understood the problem better.

## Building and Testing

### Prerequisites

- Java 21 JDK (`JAVA_HOME` pointing to JDK 21)
- Docker daemon (running, required for Testcontainers PostgreSQL integration tests)

### Commands

Compile application classes:

```bash
./mvnw clean compile
```

Run test suite (including Testcontainers PostgreSQL context verification):

```bash
./mvnw clean test
```

Package executable JAR:

```bash
./mvnw clean package
```

## Local Development Environment

### 1. Start Local PostgreSQL

Copy the sample environment file if custom ports or credentials are required:

```bash
cp .env.example .env
```

Start the PostgreSQL service in the background:

```bash
docker compose up -d
```

Verify that the container is healthy:

```bash
docker compose ps
```

### 2. Application Startup

Run the application locally against the local PostgreSQL container (executing Flyway migrations on startup):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Actuator health endpoints become accessible at `http://localhost:8080/actuator/health`.

### 3. Stop or Reset Database

Stop the container while preserving persisted data:

```bash
docker compose down
```

Stop and completely reset the database (wipes all tables and the volume):

```bash
docker compose down -v
```

## Current status

**Stage: Phase 1 — Foundation (Local Environment & Persistence Baseline)**

At this point:
- Spring Boot 3.3.5 and Java 21 project skeleton initialized.
- Maven Wrapper configured (`./mvnw` and `mvnw.cmd`).
- Minimal Docker Compose setup for local PostgreSQL development with `.env.example`.
- Flyway migration baseline configured (`V1__init.sql`).
- Test suite verifies persistence context against real PostgreSQL via Testcontainers without falling back to H2.
- Minimal GitHub Actions CI workflow configured for automated Java 21 build and test verification.
