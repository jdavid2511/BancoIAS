package com.bancoias.api;

public record ApiEnvelope<T>(boolean success, T data, ApiError error) {

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(true, data, null);
    }

    public static ApiEnvelope<Object> error(ApiError error) {
        return new ApiEnvelope<>(false, null, error);
    }
}