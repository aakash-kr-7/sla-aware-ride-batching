package com.ridebatching.dispatch;

import com.ridebatching.api.DomainConflictException;

/** Expiry and the next offer are committed even though this response cannot be accepted. */
public class OfferExpiredException extends DomainConflictException {
    public OfferExpiredException() { super("Offer expired; retrieve the trip dispatch state for the next outcome"); }
}
