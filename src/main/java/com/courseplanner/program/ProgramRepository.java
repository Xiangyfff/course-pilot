package com.courseplanner.program;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ProgramRepository {

    public record ProgramRow(long id, long userId, String school, String major, Integer gradeYear,
                             String title, String sourceFile, String sourceText, String status,
                             double totalCredits, String warnings, int version, LocalDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public ProgramRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, String school, String major, Integer gradeYear,
                       String title, String sourceFile, String sourceHash, String sourceText) {
        KeyHolder kh = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbc.sql("""
                        INSERT INTO program
                          (user_id, school, major, grade_year, title, source_file, source_hash,
                           source_text, status, total_credits, version, created_at, updated_at)
                        VALUES (:uid, :school, :major, :year, :title, :file, :hash,
                           :text, 'PENDING', 0, 0, :now, :now)
                        """)
                .param("uid", userId)
                .param("school", school)
                .param("major", major)
                .param("year", gradeYear)
                .param("title", title)
                .param("file", sourceFile)
                .param("hash", sourceHash)
                .param("text", sourceText)
                .param("now", now)
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public Optional<ProgramRow> findById(long id) {
        return jdbc.sql("""
                        SELECT id, user_id, school, major, grade_year, title, source_file, source_text,
                               status, total_credits, warnings, version, created_at
                        FROM program WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new ProgramRow(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("school"),
                        rs.getString("major"), (Integer) rs.getObject("grade_year"),
                        rs.getString("title"), rs.getString("source_file"), rs.getString("source_text"),
                        rs.getString("status"), rs.getDouble("total_credits"),
                        rs.getString("warnings"), rs.getInt("version"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    public List<ProgramRow> listByUser(long userId) {
        return jdbc.sql("""
                        SELECT id, user_id, school, major, grade_year, title, source_file, source_text,
                               status, total_credits, warnings, version, created_at
                        FROM program WHERE user_id = :uid ORDER BY id DESC
                        """)
                .param("uid", userId)
                .query((rs, i) -> new ProgramRow(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("school"),
                        rs.getString("major"), (Integer) rs.getObject("grade_year"),
                        rs.getString("title"), rs.getString("source_file"), rs.getString("source_text"),
                        rs.getString("status"), rs.getDouble("total_credits"),
                        rs.getString("warnings"), rs.getInt("version"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    /** 乐观锁更新：并发解析同一份文档时，后写的赢家必须重新读。 */
    public boolean updateStatus(long id, String status, double totalCredits, String warnings,
                                int expectedVersion) {
        LocalDateTime now = LocalDateTime.now();
        int rows = jdbc.sql("""
                        UPDATE program
                        SET status = :status, total_credits = :total, warnings = :warn,
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND version = :ver
                        """)
                .param("status", status)
                .param("total", totalCredits)
                .param("warn", warnings)
                .param("now", now)
                .param("id", id)
                .param("ver", expectedVersion)
                .update();
        return rows == 1;
    }

    public void updateMeta(long id, String school, String major, Integer gradeYear) {
        jdbc.sql("""
                        UPDATE program SET school = :school, major = :major, grade_year = :year,
                                           updated_at = :now
                        WHERE id = :id
                        """)
                .param("school", school)
                .param("major", major)
                .param("year", gradeYear)
                .param("now", LocalDateTime.now())
                .param("id", id)
                .update();
    }

    public void updateTitle(long id, String title) {
        jdbc.sql("""
                        UPDATE program SET title = :title, updated_at = :now
                        WHERE id = :id
                        """)
                .param("title", title)
                .param("now", LocalDateTime.now())
                .param("id", id)
                .update();
    }

    public Optional<Long> findRecentByHash(long userId, String hash) {
        return jdbc.sql("SELECT id FROM program WHERE user_id = :uid AND source_hash = :h ORDER BY id DESC LIMIT 1")
                .param("uid", userId)
                .param("h", hash)
                .query(Long.class)
                .optional();
    }
}
