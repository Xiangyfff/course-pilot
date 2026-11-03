package com.courseplanner.common;

public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 可重试 = 换一次执行可能就好了（网络抖动、模型超时）；解析不出课程这种重试三次也没用。 */
    private final boolean retryable;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, true);
    }

    public BizException(ErrorCode errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = true;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + " 不存在");
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    public static BizException forbidden() {
        return new BizException(ErrorCode.FORBIDDEN, "无权访问该资源");
    }

    /** 永久失败：重试不会改变结果，直接进死信。 */
    public static BizException permanent(ErrorCode code, String message) {
        return new BizException(code, message, false);
    }
}
