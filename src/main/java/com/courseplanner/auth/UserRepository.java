package com.courseplanner.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class UserRepository {

    public record UserRow(long id, String username, String passwordHash, String displayName, String role) {
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByUsername(String username) {
        return jdbc.sql("""
                        SELECT id, username, password_hash, display_name, role
                        FROM app_user WHERE username = :u
                        """)
                .param("u", username)
                .query((rs, i) -> new UserRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role")))
                .optional();
    }

    public Optional<UserRow> findById(long id) {
        return jdbc.sql("""
                        SELECT id, username, password_hash, display_name, role
                        FROM app_user WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new UserRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role")))
                .optional();
    }

    public long insert(String username, String passwordHash, String displayName) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO app_user (username, password_hash, display_name, role, created_at)
                        VALUES (:u, :p, :d, 'STUDENT', :now)
                        """)
                .param("u", username)
                .param("p", passwordHash)
                .param("d", displayName)
                .param("now", LocalDateTime.now())
                .update(kh, "id");
        return kh.getKey().longValue();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
    }
}
