package com.ridebatching.api;

/** A request whose transition conflicts with the current persisted domain state. */
public class DomainConflictException extends IllegalStateException {
    public DomainConflictException(String message) { super(message); }
}
