# ADR 007: Cache Active Serviceable Zones

Status: Implemented.

The cache avoids repeating reference-data reads while keeping booking and assignment correctness in the database. The important compromise is freshness: local eviction covers repository writes, while external changes remain subject to expiry.

`ServiceableZoneRepository.findByActiveTrue` caches active circular geofences in Caffeine. Each booking needs serviceability evaluation; zone changes are expected to be infrequent. PostgreSQL remains the source of truth.

`CacheConfig` declares `activeZones`, maximum size 100, five-minute expiry after write and statistics. The no-argument lookup caches one list entry, not individual coordinates. Population is lazy.

Repository save/delete methods, including flush and bulk variants, evict locally. A transaction-aware wrapper applies cache publication/eviction after commit, so rollback cannot publish uncommitted zones and a read completed before deletion commits cannot leave the old value cached afterward. Direct SQL and other processes do not invalidate this cache. A slow read publishing after a competing commit still needs stronger coherence; multi-instance/external freshness is TTL-bounded.

Booking deduplication, trip feasibility/capacity, driver assignment and batching claims are not cached: their guarantees require transactional state, row locks and unique constraints.

`ServiceabilityCacheIntegrationTest` checks lazy population, direct SQL staleness, save/saveAndFlush and deleteById eviction, an overlapping read/delete commit and rollback isolation. TTL timing and all write variants are not exhaustively tested. A distributed cache is not justified by the current local design.
