package com.courseplanner.extract;

public interface LlmClient {

    record Request(String scene, String systemPrompt, String userPrompt, boolean jsonMode) {
    }

    record Result(String content, int promptTokens, int completionTokens, long latencyMs, String model) {
    }

    /** 失败直接抛异常，由调用方决定降级还是重试。 */
    Result chat(Request request);
}
