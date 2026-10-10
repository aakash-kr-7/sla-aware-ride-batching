# Problem Analysis

## Actors

Employee
Driver
Admin
Approver

## Core entities

Booking
Trip
Driver
Vehicle
ServiceableZone
DriverOffer

## Booking states

PENDING
CONFIRMED
PICKED_UP
DROPPED
CANCELLED

## Hard constraints

H1. Pickup must belong to a serviceable zone.
H2. Equivalent request submissions must resolve to one booking.
H3. Vehicle capacity cannot be exceeded.
H4. Pickup SLA cannot be violated by batching.
H5. Driver on active break cannot receive trip offer.
H6. Rejected driver must not immediately receive same trip again.
H7. Cancellation replanning must remain local to affected trip.

## Optimisation objectives

O1. Increase occupancy.
O2. Reduce unnecessary vehicles.
O3. Minimise rider detour.
O4. Prefer nearby available drivers.

Hard constraints always take priority over optimisation objectives.