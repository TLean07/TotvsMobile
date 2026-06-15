package com.tlean07.totvs.network;

import androidx.annotation.Nullable;

/** Small sealed-style result wrapper so the UI never touches raw exceptions. */
public class NetworkResult<T> {

    @Nullable
    public final T data;
    @Nullable
    public final String errorMessage;
    public final boolean success;

    private NetworkResult(boolean success, @Nullable T data, @Nullable String errorMessage) {
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
    }

    public static <T> NetworkResult<T> ok(@Nullable T data) {
        return new NetworkResult<>(true, data, null);
    }

    public static <T> NetworkResult<T> error(String message) {
        return new NetworkResult<>(false, null, message);
    }
}
