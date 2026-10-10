# Assumptions and Open Decisions

This document records places where the case study leaves implementation
details open.

The intention is to make those decisions explicit instead of allowing them
to appear accidentally inside the code.


## A01 — Batching window

Case study example: 15 minutes.

Initial decision:
Use configurable 15-minute windows.

Status:
Tentatively accepted.


## A02 — Pickup SLA

Case study example: 20 minutes.

Initial decision:
Use configurable 20-minute SLA.

Status:
Tentatively accepted.


## A03 — Deduplication key

Requirement:
same employee + same pickup/drop + same slot must create one request.

Question:
What exactly should "same slot" mean?

Possible interpretation:
derived batching-window identifier.

Status:
OPEN


## A04 — Direction compatibility

Requirement:
cluster riders travelling in the same general direction.

Possible approaches:
- destination zone
- bearing difference
- route overlap

Status:
OPEN — evaluate before batching implementation.


## A05 — Maximum acceptable detour

Requirement says detour should remain small but does not prescribe
an exact threshold.

Decision:
Not yet chosen.

Status:
OPEN