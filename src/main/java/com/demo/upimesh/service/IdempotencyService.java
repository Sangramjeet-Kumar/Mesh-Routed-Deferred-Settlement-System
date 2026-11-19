package com.demo.upimesh.service;

/** Shared claim contract. Errors must propagate; an outage is never a duplicate. */
public interface IdempotencyService {
    boolean claim(String packetHash);
    int size();
    void clear();
}
