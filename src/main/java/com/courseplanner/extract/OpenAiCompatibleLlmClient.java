package com.courseplanner.extract;

import com.courseplanner.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议（OpenAI / DeepSeek / 通义 / Ollama / vLLM 都能用）。
 * 换厂商只改 application.yml 里的 base-url 和 model。
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public OpenAiCompatibleLlmClient(RestClient llmRestClient, ObjectMapper mapper, AppProperties props) {
        this.http = llmRestClient;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public Result chat(Request request) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getLlm().getModel());
        body.put("temperature", 0.0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        if (request.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            String raw = http.post()
                    .uri(props.getLlm().getBaseUrl() + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.getLlm().getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = mapper.readTree(raw);
            String content = node.path("choices").path(0).path("message").path("content").asText();
            int promptTokens = node.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = node.path("usage").path("completion_tokens").asInt(0);
            long latency = System.currentTimeMillis() - start;
            log.info("llm scene={} latency={}ms tokens={}/{}", request.scene(), latency,
                    promptTokens, completionTokens);
            return new Result(content, promptTokens, completionTokens, latency, props.getLlm().getModel());
        } catch (Exception e) {
            throw new LlmExceptions.LlmUnavailable("模型调用失败：" + e.getMessage(), e);
        }
    }
}
