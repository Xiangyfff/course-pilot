package com.courseplanner.common;

/**
 * 统一响应体。code=0 表示成功。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
        return new ApiResponse<>(code.code(), message, null);
    }
}
