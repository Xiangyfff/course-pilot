package com.courseplanner.extract;

public final class LlmExceptions {

    private LlmExceptions() {
    }

    public static class LlmUnavailable extends RuntimeException {
        public LlmUnavailable(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmUnavailable(String message) {
            super(message);
        }
    }
}
