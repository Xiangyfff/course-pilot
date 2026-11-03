package com.courseplanner.common;

/**
 * 业务错误码。前端只依赖 code 做分支，message 只用于展示。
 */
public enum ErrorCode {

    BAD_REQUEST(40000, 400),
    UNAUTHORIZED(40100, 401),
    FORBIDDEN(40300, 403),
    NOT_FOUND(40400, 404),
    CONFLICT(40900, 409),

    FILE_INVALID(42201, 422),
    LLM_FAILED(50201, 502),
    SOLVE_FAILED(50202, 502),

    RATE_LIMITED(42900, 429),
    INTERNAL(50000, 500);

    private final int code;
    private final int httpStatus;

    ErrorCode(int code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
