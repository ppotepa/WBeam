package com.wbeam.stream;

import java.io.IOException;

/**
 * Stream-specific exception wrapping transport and decode failures.
 */
public final class StreamException extends IOException {
    public StreamException(String message) {
        super(message);
    }

    public StreamException(String message, Throwable cause) {
        super(message, cause);
    }

    public StreamException(Throwable cause) {
        super(cause);
    }
}
