package com.courseplanner.qa.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆：只存「跨会话仍然成立」的用户偏好，不存对话原文。
 *
 * 三个刻意的限制：
 * 1. 写入用规则而不是模型——抽错一条偏好会污染之后所有回答，规则的错误率更低、可解释；
 * 2. 只保留最近 8 条，避免 prompt 无限膨胀；
 * 3. 明确不存课程、学分这类事实（那些永远从数据库读，记忆里的事实一定会过期）。
 */
@Service
public class MemoryService {

    /** 识别到的偏好：key 用于去重，value 是给模型看的一句话。 */
    public record Memory(String key, String value, String kind) {
    }

    private static final int MAX_ITEMS = 8;

    private final JdbcClient jdbc;

    public MemoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 从一轮问答里抽偏好。只认几种明确说法，抽不到就什么都不存。 */
    public List<Memory> extract(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<Memory> found = new ArrayList<>();
        String q = question.replaceAll("\\s+", "");

        if (q.contains("早点毕业") || q.contains("尽快毕业") || q.contains("早毕业")) {
            found.add(new Memory("pref:graduation", "希望尽早毕业，排课优先压缩学期数", "preference"));
        }
        if (q.contains("轻松") || q.contains("压力小") || q.contains("别太累") || q.contains("少排点")
                || (q.contains("压力") && (q.contains("别") || q.contains("不") || q.contains("小")))) {
            found.add(new Memory("pref:load", "希望每学期负担轻，排课优先均衡学分", "preference"));
        }
        if (q.contains("早八") && (q.contains("不") || q.contains("别") || q.contains("避开") || q.contains("避免"))) {
            found.add(new Memory("pref:morning", "不想上早八的课", "preference"));
        }
        if (q.contains("考研") || q.contains("保研")) {
            found.add(new Memory("pref:postgrad", "有考研/保研打算，后两年需要留出复习时间", "preference"));
        }
        if (q.contains("实习")) {
            found.add(new Memory("pref:intern", "需要留出实习时间，避免某学期课程过满", "preference"));
        }
        return found;
    }

    public void remember(long userId, String question) {
        for (Memory m : extract(question)) {
            upsert(userId, m, "qa");
        }
    }

    public void upsert(long userId, Memory m, String source) {
        // 不用 MERGE / ON DUPLICATE KEY：前者是 H2 方言，后者是 MySQL 方言。
        // 先 UPDATE 再 INSERT，冲突（并发同时插入）时回退到 UPDATE，两个库都能跑。
        int updated = jdbc.sql("""
                        UPDATE user_memory
                        SET kind = :kind, mem_value = :value, source = :source, updated_at = :now
                        WHERE user_id = :userId AND mem_key = :key
                        """)
                .param("kind", m.kind())
                .param("value", m.value())
                .param("source", source)
                .param("now", LocalDateTime.now())
                .param("userId", userId)
                .param("key", m.key())
                .update();
        if (updated > 0) {
            return;
        }
        try {
            jdbc.sql("""
                            INSERT INTO user_memory (user_id, kind, mem_key, mem_value, source, updated_at)
                            VALUES (:userId, :kind, :key, :value, :source, :now)
                            """)
                    .param("userId", userId)
                    .param("kind", m.kind())
                    .param("key", m.key())
                    .param("value", m.value())
                    .param("source", source)
                    .param("now", LocalDateTime.now())
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            jdbc.sql("UPDATE user_memory SET mem_value = :value, updated_at = :now WHERE user_id = :userId AND mem_key = :key")
                    .param("value", m.value())
                    .param("now", LocalDateTime.now())
                    .param("userId", userId)
                    .param("key", m.key())
                    .update();
        }
    }

    /** 给 prompt 用的记忆行，最多 8 条，最新的在前。 */
    public List<String> context(long userId) {
        return jdbc.sql("""
                        SELECT mem_value FROM user_memory
                        WHERE user_id = :userId
                        ORDER BY updated_at DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", MAX_ITEMS)
                .query((rs, i) -> rs.getString("mem_value"))
                .list();
    }

    public List<Map<String, Object>> list(long userId) {
        return jdbc.sql("""
                        SELECT kind, mem_key, mem_value, source, updated_at FROM user_memory
                        WHERE user_id = :userId
                        ORDER BY updated_at DESC
                        """)
                .param("userId", userId)
                .query((rs, i) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("kind", rs.getString("kind"));
                    row.put("key", rs.getString("mem_key"));
                    row.put("value", rs.getString("mem_value"));
                    row.put("source", rs.getString("source"));
                    row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
                    return row;
                })
                .list();
    }

    public int forget(long userId, String key) {
        return jdbc.sql("DELETE FROM user_memory WHERE user_id = :userId AND mem_key = :key")
                .param("userId", userId)
                .param("key", key)
                .update();
    }
}
