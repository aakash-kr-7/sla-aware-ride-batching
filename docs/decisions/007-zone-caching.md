# ADR 007: Cache Active Serviceable Zones

Status: Implemented.

`ServiceableZoneRepository.findByActiveTrue` caches active circular geofences in Caffeine. Each booking needs serviceability evaluation; zone changes are expected to be infrequent. PostgreSQL remains the source of truth.

`CacheConfig` declares `activeZones`, maximum size 100, five-minute expiry after write and statistics. The no-argument lookup caches one list entry, not individual coordinates. Population is lazy.

Repository save/saveAll/delete/deleteAll evict locally. Direct SQL, other inherited write variants and other processes do not. A deactivated zone may temporarily accept requests through stale cached data; multi-instance freshness is TTL-bounded.

Booking deduplication, trip feasibility/capacity, driver assignment and batching claims are not cached: their guarantees require transactional state, row locks and unique constraints.

`ServiceabilityCacheIntegrationTest` checks lazy population, cached reads despite direct SQL and repository-save eviction. TTL timing and all write variants are not exhaustively tested. A distributed cache is not justified by the current local design.
