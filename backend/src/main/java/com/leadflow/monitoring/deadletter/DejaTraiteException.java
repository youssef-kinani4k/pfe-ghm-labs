package com.leadflow.monitoring.deadletter;

/** Traduite en 409 : une ligne deja REPLAYED ou DISCARDED ne se retraite pas. */
public class DejaTraiteException extends RuntimeException {

    public DejaTraiteException(String message) {
        super(message);
    }
}
