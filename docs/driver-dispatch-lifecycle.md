# Driver Dispatch Lifecycle

## Sequence Diagram

This diagram illustrates the process of dispatching a batched trip, handling driver rejections, cool-offs, and final acceptance. It highlights the transactional and concurrency boundaries.

```mermaid
sequenceDiagram
    participant Batching as Batching Service
    participant Dispatch as Dispatch Service
    participant Policy as Eligibility & Ranker
    participant DB as PostgreSQL
    participant Driver1 as Driver 1 (Near)
    participant Driver2 as Driver 2 (Far)

    Batching->>Dispatch: batched trip ready (Status: PLANNED)
    
    rect rgb(240, 248, 255)
        note right of Dispatch: Transaction: dispatchTrip
        Dispatch->>DB: fetch all drivers
        Dispatch->>Policy: evaluate eligibility & cool-off
        Policy-->>Dispatch: eligible drivers list
        Dispatch->>Policy: rank by proximity
        Policy-->>Dispatch: Driver 1, Driver 2
        Dispatch->>DB: save DriverOffer (Status: OFFERED, Driver: Driver 1)
    end
    
    Dispatch-->>Driver1: send offer
    
    Driver1->>Dispatch: rejectOffer
    rect rgb(255, 240, 240)
        note right of Dispatch: Transaction: rejectOffer
        Dispatch->>DB: update DriverOffer (Status: REJECTED)
    end
    
    note over Dispatch,Driver1: Driver 1 enters Cool-Off period for this trip
    
    Dispatch->>Dispatch: trigger next dispatch cycle
    
    rect rgb(240, 248, 255)
        note right of Dispatch: Transaction: dispatchTrip
        Dispatch->>DB: fetch all drivers
        Dispatch->>Policy: evaluate eligibility & cool-off
        note over Policy: Driver 1 is excluded (cool-off)
        Policy-->>Dispatch: eligible drivers list
        Dispatch->>Policy: rank by proximity
        Policy-->>Dispatch: Driver 2
        Dispatch->>DB: save DriverOffer (Status: OFFERED, Driver: Driver 2)
    end
    
    Dispatch-->>Driver2: send offer
    
    Driver2->>Dispatch: acceptOffer
    rect rgb(240, 255, 240)
        note right of Dispatch: Transaction: acceptOffer
        Dispatch->>DB: update DriverOffer (Status: ACCEPTED)
        note right of DB: PESSIMISTIC_WRITE Lock on Trip
        Dispatch->>DB: SELECT * FROM trip WHERE id = ? FOR UPDATE
        Dispatch->>DB: update Trip (Status: DISPATCHED)
        Dispatch->>DB: update Driver (Status: ON_TRIP)
    end
```

## DriverOffer State Diagram

The `DriverOffer` tracks the lifecycle of an offer made to a driver for a specific trip. 
It ensures that an offer can only be acted upon if it is in a valid state.

```mermaid
stateDiagram-v2
    [*] --> OFFERED : Created

    OFFERED --> ACCEPTED : accept()
    OFFERED --> REJECTED : reject()
    OFFERED --> EXPIRED : timeout() / expire()

    ACCEPTED --> [*]
    REJECTED --> [*]
    EXPIRED --> [*]
```
