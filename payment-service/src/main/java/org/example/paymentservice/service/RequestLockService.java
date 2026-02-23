package org.example.paymentservice.service;

public interface RequestLockService {

    boolean acquire(String key);

    void release(String key);
}
