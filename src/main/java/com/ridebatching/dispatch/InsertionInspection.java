package com.ridebatching.dispatch;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Fresh hypothetical route-constraint check for one candidate booking. Not a historical decision, assignment or reservation; ignores new-batch window/zone/direction grouping.")
public record InsertionInspection(UUID candidateBookingId, Instant evaluatedAt, Instant routeStartTime,
                                  @Schema(description = "Unassigned first pickup at max(current time, original planning start); no driver approach in this diagnostic.")
                                  String timingBasis,
                                  boolean feasible,
                                  @Schema(description = "Alternatives actually constraint-checked. Cost pruning can skip equally/more expensive alternatives after finding a feasible route.")
                                  int alternativesEvaluated,
                                  @Schema(description = "Counts and one representative example per first-failure constraint. Rejections of some alternatives do not imply the booking cannot fit when feasible=true.")
                                  List<RejectionReason> rejections,
                                  @Schema(description = "Cheapest feasible hypothetical route with fresh estimates; empty if every insertion fails. Existing stored route is unchanged.")
                                  List<DispatchView.Stop> proposedStops) {
    public record RejectionReason(String constraintCode, int alternativesRejected, UUID affectedBooking,
                                  int examplePeakPassengers, Instant estimatedPickupAt, Instant pickupSlaDeadline,
                                  Long directTravelSeconds, Long sharedTravelSeconds) {}
}
