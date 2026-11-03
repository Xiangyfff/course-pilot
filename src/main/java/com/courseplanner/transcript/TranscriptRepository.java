package com.courseplanner.transcript;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class TranscriptRepository {

    public record TranscriptRow(long id, long userId, String fileName, String status, LocalDateTime createdAt) {
    }

    public record PassedCourse(String code, String name, double credits, String term) {
    }

    private final JdbcClient jdbc;

    public TranscriptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, String fileName, String status) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO transcript (user_id, file_name, status, created_at)
                        VALUES (:u, :f, :s, :now)
                        """)
                .param("u", userId).param("f", fileName).param("s", status)
                .param("now", LocalDateTime.now())
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    @Transactional
    public void replaceCourses(long transcriptId, List<PassedCourse> courses) {
        jdbc.sql("DELETE FROM transcript_course WHERE transcript_id = :t")
                .param("t", transcriptId).update();
        for (PassedCourse c : courses) {
            jdbc.sql("""
                            INSERT INTO transcript_course
                              (transcript_id, code, name, credits, score, term, passed)
                            VALUES (:t, :code, :name, :credits, NULL, :term, TRUE)
                            """)
                    .param("t", transcriptId)
                    .param("code", c.code())
                    .param("name", c.name())
                    .param("credits", c.credits())
                    .param("term", c.term())
                    .update();
        }
    }

    public void updateStatus(long id, String status) {
        jdbc.sql("UPDATE transcript SET status = :s WHERE id = :id")
                .param("s", status).param("id", id).update();
    }

    public List<PassedCourse> findCourses(long transcriptId) {
        return jdbc.sql("""
                        SELECT code, name, credits, term FROM transcript_course
                        WHERE transcript_id = :t AND passed = TRUE ORDER BY id
                        """)
                .param("t", transcriptId)
                .query((rs, i) -> new PassedCourse(
                        rs.getString("code"), rs.getString("name"),
                        rs.getDouble("credits"), rs.getString("term")))
                .list();
    }

    public java.util.Optional<Long> findLatestId(long userId) {
        return jdbc.sql("SELECT id FROM transcript WHERE user_id = :u ORDER BY id DESC LIMIT 1")
                .param("u", userId).query(Long.class).optional();
    }

    public List<TranscriptRow> listByUser(long userId) {
        return jdbc.sql("""
                        SELECT id, user_id, file_name, status, created_at FROM transcript
                        WHERE user_id = :u ORDER BY id DESC
                        """)
                .param("u", userId)
                .query((rs, i) -> new TranscriptRow(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("file_name"),
                        rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }
}
