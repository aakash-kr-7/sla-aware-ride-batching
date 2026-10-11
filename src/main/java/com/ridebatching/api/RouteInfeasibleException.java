package com.ridebatching.api;

import com.ridebatching.batching.FeasibilityResult;

/** A safe algorithm reason from a controlled constraint, without SQL or internal exception details. */
public class RouteInfeasibleException extends DomainConflictException {
    private final String constraintCode;
    public RouteInfeasibleException(FeasibilityResult.RejectionDetails reason) {
        super("Route would violate " + reason.constraintCode());
        this.constraintCode = reason.constraintCode();
    }
    public String getConstraintCode() { return constraintCode; }
}
