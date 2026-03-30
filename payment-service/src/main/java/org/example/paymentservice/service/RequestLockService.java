package org.example.paymentservice.service;

/**
 * Idempotency lock contract for request processing.
 */
public interface RequestLockService {

    /** Attempts to acquire lock for a key. */
    boolean acquire(String key);

    /** Releases lock for a key if owned by current instance. */
    void release(String key);
}
