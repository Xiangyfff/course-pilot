package com.courseplanner.extract;

import com.courseplanner.config.AppProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class LlmCallLogRepository {

    private final JdbcClient jdbc;
    private final AppProperties props;

    public LlmCallLogRepository(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public void record(long userId, Long jobId, String scene, LlmClient.Result result) {
        long costMicros = costMicros(result.promptTokens(), result.completionTokens());
        jdbc.sql("""
                        INSERT INTO llm_call_log
                          (user_id, job_id, scene, model, prompt_tokens, completion_tokens,
                           cost_micros, latency_ms, success, error_message, created_at)
                        VALUES (:u, :j, :s, :m, :pt, :ct, :cost, :lat, TRUE, NULL, :now)
                        """)
                .param("u", userId).param("j", jobId).param("s", scene).param("m", result.model())
                .param("pt", result.promptTokens()).param("ct", result.completionTokens())
                .param("cost", costMicros).param("lat", result.latencyMs())
                .param("now", LocalDateTime.now())
                .update();
    }

    public void recordFailure(long userId, Long jobId, String scene, String error) {
        jdbc.sql("""
                        INSERT INTO llm_call_log
                          (user_id, job_id, scene, model, prompt_tokens, completion_tokens,
                           cost_micros, latency_ms, success, error_message, created_at)
                        VALUES (:u, :j, :s, :m, 0, 0, 0, 0, FALSE, :err, :now)
                        """)
                .param("u", userId).param("j", jobId).param("s", scene)
                .param("m", props.getLlm().getModel()).param("err", truncate(error))
                .param("now", LocalDateTime.now())
                .update();
    }

    public record CostSummary(long calls, long successCalls, long promptTokens, long completionTokens,
                              long costMicros, long avgLatencyMs) {
    }

    public CostSummary summary(long userId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS calls,
                               SUM(CASE WHEN success THEN 1 ELSE 0 END) AS ok,
                               COALESCE(SUM(prompt_tokens), 0) AS pt,
                               COALESCE(SUM(completion_tokens), 0) AS ct,
                               COALESCE(SUM(cost_micros), 0) AS cost,
                               COALESCE(AVG(latency_ms), 0) AS lat
                        FROM llm_call_log WHERE user_id = :u
                        """)
                .param("u", userId)
                .query((rs, i) -> new CostSummary(
                        rs.getLong("calls"), rs.getLong("ok"), rs.getLong("pt"),
                        rs.getLong("ct"), rs.getLong("cost"), rs.getLong("lat")))
                .single();
    }

    private long costMicros(int promptTokens, int completionTokens) {
        // 单价按「分/百万 token」配置，算出的是千分之一分（micros）
        long p = promptTokens * props.getLlm().getPricePerMillionPromptCents() * 1000L / 1_000_000L;
        long c = completionTokens * props.getLlm().getPricePerMillionCompletionCents() * 1000L / 1_000_000L;
        return p + c;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
