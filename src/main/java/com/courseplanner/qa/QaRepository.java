package com.courseplanner.qa;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class QaRepository {

    public record Message(long id, String role, String content, String citations, LocalDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public QaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long userId, Long programId, String role, String content, String citations) {
        jdbc.sql("""
                        INSERT INTO qa_message (user_id, program_id, role, content, citations, created_at)
                        VALUES (:u, :p, :r, :c, :cit, :now)
                        """)
                .param("u", userId).param("p", programId).param("r", role)
                .param("c", content).param("cit", citations)
                .param("now", LocalDateTime.now())
                .update();
    }

    public List<Message> history(long userId, Long programId, int limit) {
        return jdbc.sql("""
                        SELECT id, role, content, citations, created_at FROM qa_message
                        WHERE user_id = :u AND (:p IS NULL OR program_id = :p)
                        ORDER BY id DESC LIMIT :lim
                        """)
                .param("u", userId).param("p", programId).param("lim", limit)
                .query((rs, i) -> new Message(
                        rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("citations"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }
}
