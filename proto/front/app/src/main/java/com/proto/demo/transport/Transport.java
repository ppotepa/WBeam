package com.proto.demo.transport;

/**
 * Contract for streaming transports.
 * Single responsibility: define the lifecycle contract (run + stop).
 */
public interface Transport extends Runnable {
    /** Signal the transport to stop consuming frames and exit its run() loop. */
    void stop();
}
