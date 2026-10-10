# Trip Mutation Flows

These flows illustrate how active trips can be mutated dynamically through late requests or cancellations, and how concurrent safety is maintained.

## Late Request Insertion

When a new booking is requested, the system evaluates if it can be inserted into an existing candidate trip without violating capacity, SLA, or detour constraints.

```mermaid
sequenceDiagram
    participant B as Booking
    participant BS as BookingService
    participant MS as BatchingService
    participant T as Trip (Candidate)
    participant DB as PostgreSQL
    participant UT as Unrelated Trips

    B->>BS: createBooking()
    BS->>MS: attemptLateInsertion()
    
    rect rgb(240, 248, 255)
        note right of MS: Transaction: attemptLateInsertion
        MS->>DB: SELECT * FROM trip WHERE status IN (PLANNED, DISPATCHED) FOR UPDATE
        DB-->>MS: Lock Acquired
        MS->>T: evaluate capacity/SLA/detour
        alt Insertion Feasible
            MS->>T: addBooking(newBooking)
            MS->>DB: save(Trip)
            MS-->>BS: true (Inserted)
        else Insertion Not Feasible
            MS-->>BS: false (Remain pending)
        end
    end
    
    rect rgb(245, 245, 245)
        note right of UT: Boundary Check
        MS-->>UT: Unrelated trips remain completely untouched
    end
```

## Booking Cancellation (Local Replan)

If a booking assigned to a trip is cancelled, the trip is replanned locally by removing the booking's stops.

```mermaid
sequenceDiagram
    participant U as User/Driver
    participant BS as BookingService
    participant T as Trip
    participant DB as PostgreSQL
    participant UT as Unrelated Trips

    U->>BS: cancelBooking(bookingId)
    
    rect rgb(255, 240, 240)
        note right of BS: Transaction: cancelBooking
        BS->>DB: SELECT * FROM booking WHERE id = ?
        BS->>DB: update Booking (Status: CANCELLED)
        BS->>DB: SELECT * FROM trip WHERE id = ? FOR UPDATE
        DB-->>BS: Lock Acquired
        BS->>T: removeBooking(bookingId)
        note over T: rebuild route, cancel trip if empty
        BS->>DB: save(Trip)
    end
    
    rect rgb(245, 245, 245)
        note right of UT: Boundary Check
        BS-->>UT: Unrelated trips remain completely untouched
    end
```
