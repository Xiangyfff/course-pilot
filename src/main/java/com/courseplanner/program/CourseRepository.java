package com.courseplanner.program;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CourseRepository {

    public record ModuleRequirement(long id, String code, String name, double minCredits) {
    }

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public CourseRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /**
     * 整份方案覆盖写入：解析重跑时先删后插，避免同一 program 出现重复课程。
     * 放在一个事务里，保证「读者看不到写了一半的方案」。
     */
    @Transactional
    public void replaceAll(long programId, ProgramDraft draft) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.sql("DELETE FROM course_prereq WHERE program_id = :p").param("p", programId).update();
        jdbc.sql("DELETE FROM course WHERE program_id = :p").param("p", programId).update();
        jdbc.sql("DELETE FROM module_requirement WHERE program_id = :p").param("p", programId).update();

        // 批量写入：原来一门课一次 round-trip，40 门课 + 先修边要跑 60+ 次，
        // 在 MySQL 上实测约 200ms，批量后降到几十毫秒（JDBC URL 开了 rewriteBatchedStatements）。
        if (!draft.modules().isEmpty()) {
            List<SqlParameterSource> modules = new ArrayList<>();
            for (ProgramDraft.ModuleDraft m : draft.modules()) {
                modules.add(new MapSqlParameterSource()
                        .addValue("p", programId).addValue("c", m.code()).addValue("n", m.name())
                        .addValue("min", m.minCredits()).addValue("now", now));
            }
            batch.batchUpdate("""
                    INSERT INTO module_requirement
                      (program_id, module_code, module_name, min_credits, created_at)
                    VALUES (:p, :c, :n, :min, :now)
                    """, modules.toArray(SqlParameterSource[]::new));
        }

        if (!draft.courses().isEmpty()) {
            List<SqlParameterSource> courses = new ArrayList<>();
            List<SqlParameterSource> prereqs = new ArrayList<>();
            for (ProgramDraft.CourseDraft c : draft.courses()) {
                courses.add(new MapSqlParameterSource()
                        .addValue("p", programId)
                        .addValue("code", c.code())
                        .addValue("name", c.name())
                        .addValue("credits", c.credits())
                        .addValue("module", c.module())
                        .addValue("type", c.type())
                        .addValue("terms", String.join(",", c.terms().stream().map(String::valueOf).toList()))
                        .addValue("now", now));
                for (String pre : c.prereqs()) {
                    prereqs.add(new MapSqlParameterSource()
                            .addValue("p", programId).addValue("c", c.code())
                            .addValue("pre", pre).addValue("now", now));
                }
            }
            batch.batchUpdate("""
                    INSERT INTO course
                      (program_id, code, name, credits, module_code, course_type, offered_terms, created_at)
                    VALUES (:p, :code, :name, :credits, :module, :type, :terms, :now)
                    """, courses.toArray(SqlParameterSource[]::new));

            if (!prereqs.isEmpty()) {
                batch.batchUpdate("""
                        INSERT INTO course_prereq (program_id, course_code, prereq_code, created_at)
                        VALUES (:p, :c, :pre, :now)
                        """, prereqs.toArray(SqlParameterSource[]::new));
            }
        }
    }

    public List<Course> findCourses(long programId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT id, code, name, credits, module_code, course_type, offered_terms
                        FROM course WHERE program_id = :p ORDER BY id
                        """)
                .param("p", programId)
                .query((rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("credits", rs.getDouble("credits"));
                    m.put("module", rs.getString("module_code"));
                    m.put("type", rs.getString("course_type"));
                    m.put("terms", rs.getString("offered_terms"));
                    return m;
                })
                .list();

        Map<String, List<String>> prereqs = new HashMap<>();
        jdbc.sql("SELECT course_code, prereq_code FROM course_prereq WHERE program_id = :p")
                .param("p", programId)
                .query((rs, i) -> {
                    prereqs.computeIfAbsent(rs.getString("course_code"), k -> new ArrayList<>())
                            .add(rs.getString("prereq_code"));
                    return null;
                })
                .list();

        List<Course> result = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("code");
            result.add(new Course(
                    (Long) r.get("id"), code, (String) r.get("name"), (Double) r.get("credits"),
                    (String) r.get("module"), (String) r.get("type"),
                    parseTerms((String) r.get("terms")),
                    prereqs.getOrDefault(code, List.of())));
        }
        return result;
    }

    public List<ModuleRequirement> findModules(long programId) {
        return jdbc.sql("""
                        SELECT id, module_code, module_name, min_credits
                        FROM module_requirement WHERE program_id = :p ORDER BY id
                        """)
                .param("p", programId)
                .query((rs, i) -> new ModuleRequirement(
                        rs.getLong("id"), rs.getString("module_code"),
                        rs.getString("module_name"), rs.getDouble("min_credits")))
                .list();
    }

    public long countCourses(long programId) {
        return jdbc.sql("SELECT COUNT(*) FROM course WHERE program_id = :p")
                .param("p", programId).query(Long.class).single();
    }

    private static List<Integer> parseTerms(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> terms = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                terms.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过，不要因为一个学期号让整份方案不可用
            }
        }
        return terms;
    }
}
