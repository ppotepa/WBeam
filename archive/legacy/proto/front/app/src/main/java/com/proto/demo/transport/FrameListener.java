package com.proto.demo.transport;

/**
 * Callback invoked by a transport whenever a complete JPEG frame arrives.
 */
public interface FrameListener {
    void onFrame(byte[] data, int len);
}
