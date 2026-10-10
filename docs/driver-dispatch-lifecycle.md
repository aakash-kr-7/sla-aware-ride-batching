# Driver Dispatch Lifecycle

Dispatch is a transactional service with integration coverage. There is no driver HTTP API, outbound notification transport or automatic batching-to-dispatch flow.

1. `dispatchTrip` filters offline/on-break/assigned drivers and recent trip-specific rejections, then ranks by Haversine distance and driver ID.
2. Nearest eligible driver gets a persisted `OFFERED` record; no eligible driver returns empty. Trip is not marked `OFFERED`; multiple outstanding offers are possible.
3. `rejectOffer` sets `REJECTED`. A later explicit dispatch call can select another driver; rejection does not automatically re-offer.
4. `acceptOffer` updates offer, locks trip, assigns driver and records active trip. Assignment state and unique accepted-offer index prevent two winners for one trip.

Offer transitions: `OFFERED -> ACCEPTED / REJECTED / EXPIRED`. `expire()` has no automatic timer; offers store created/updated times, not deadlines. Cool-off is five minutes, compared to offer creation time; trip changes do not reset history.

Assigned driver status remains `AVAILABLE`; eligibility also checks active trip ID. Completion has no execution API. Dispatch does not compare driver capacity to route occupancy.

See [assumptions](assumptions.md), [domain model](domain-model.md), [failure limits](failure-modes.md).
