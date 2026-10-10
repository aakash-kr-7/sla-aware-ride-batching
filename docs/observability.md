# Observability and Health Checks

This document covers local health checks and observability capabilities introduced into the system.

## Health Diagnostics

Spring Boot Actuator is configured to expose standard liveness and readiness probes natively. Sensitive information such as environment variables, properties, and bean definitions are explicitly disabled for security.

The following endpoints are accessible without authorization:
* `GET /actuator/health` - Aggregate health status including database connectivity.
* `GET /actuator/health/liveness` - Kubernetes-compatible liveness probe.
* `GET /actuator/health/readiness` - Kubernetes-compatible readiness probe.
* `GET /actuator/info` - General application information (no custom info contributor).

Database connectivity belongs to aggregate health. Liveness/readiness do not include the database by default. `GET /actuator/metrics` is also public under the existing security policy.

## Metrics

Operational metrics are exposed natively through Micrometer and formatted for Prometheus scraping.
* `GET /actuator/prometheus` - Prometheus scrape endpoint.

### Key Domain Metrics
The system explicitly measures core batching and dispatch events without tracking high-cardinality data like user UUIDs or GPS coordinates.

* `bookings.requests` (Counter) - Total bookings initiated.
* `bookings.serviceability_rejections` (Counter) - Bookings rejected due to pickup location outside zones.
* `bookings.deduplication_hits` (Counter) - Prevented duplicate booking races.
* `bookings.cancellation_replans` (Counter) - Cancellations removing stops from an assigned trip (no route split or full feasibility recheck).
* `batching.duration` (Timer) - Time taken for a batching cycle to execute.
* `batching.trips_formed` (Counter) - Total number of trips created during batching.
* `batching.sla_rejections` (Counter) - Candidate trips rejected during evaluation due to Pickup SLA constraints.
* `batching.detour_rejections` (Counter) - Candidate trips rejected due to detour time limits.
* `batching.trip_splits` (Counter) - Situations where a booking could not be inserted into existing candidate trips, forcing a new trip split.
* `batching.late_insertion` (Counter) - Records success/failure when candidate planned trips exist; early return with no candidates is not counted.
* `dispatch.offers` (Counter) - Offers sent to drivers.
* `dispatch.rejections` (Counter) - Offers rejected by drivers.

## Structured Logging
A `CorrelationIdFilter` intercepts all HTTP requests. 
If `X-Correlation-Id` is passed by the client, it is injected into the SLF4J MDC (Mapped Diagnostic Context). If not passed, a new UUID is generated automatically.
The console log pattern includes `correlationId`, allowing request-related service messages to be connected. Scheduled jobs have no HTTP correlation context. Logs use fields in messages and MDC; no JSON log encoder is configured.
