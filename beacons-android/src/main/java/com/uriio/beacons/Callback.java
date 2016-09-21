package com.uriio.beacons;

/**
 * Generic listener for the result of an operation.
 * @param <T>    Specialized result type
 */
public interface Callback<T> {
    /**
     * Result callback.
     * @param result    The call's result, or null if there was an error.
     * @param error     Encountered error, or null if none.
     */
    void onResult(T result, Throwable error);
}
