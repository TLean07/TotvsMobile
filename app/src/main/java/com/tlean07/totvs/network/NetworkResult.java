package com.tlean07.totvs.network;

import androidx.annotation.Nullable;

/** Small sealed-style result wrapper so the UI never touches raw exceptions. */
public class NetworkResult<T> {

    @Nullable
    public final T data;
    @Nullable
    public final String errorMessage;
    public final boolean success;
    /** True when the request never reached the server (no connection, timeout). */
    public final boolean networkError;

    private NetworkResult(boolean success, @Nullable T data, @Nullable String errorMessage, boolean networkError) {
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
        this.networkError = networkError;
    }

    public static <T> NetworkResult<T> ok(@Nullable T data) {
        return new NetworkResult<>(true, data, null, false);
    }

    public static <T> NetworkResult<T> error(String message) {
        return new NetworkResult<>(false, null, message, false);
    }

    public static <T> NetworkResult<T> networkError(String message) {
        return new NetworkResult<>(false, null, message, true);
    }
}
