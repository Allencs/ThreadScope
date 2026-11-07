package com.threadscope.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!test")
public class UserRepository {

    private static final String COLUMNS = """
        id, username, password_hash, role, enabled, must_change_password, session_version, created_at, last_login_at
        """;

    private static final RowMapper<AppUser> MAPPER = (rs, n) -> {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            rs.getBoolean("enabled"),
            rs.getBoolean("must_change_password"),
            rs.getInt("session_version"),
            rs.getTimestamp("created_at").toInstant(),
            lastLogin == null ? null : lastLogin.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE username = ?", MAPPER, username)
            .stream().findFirst();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<AppUser> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user ORDER BY id", MAPPER);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class);
        return count == null ? 0 : count;
    }

    public long countEnabledAdmins() {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE role = 'ADMIN' AND enabled", Long.class);
        return count == null ? 0 : count;
    }

    public long insert(String username, String passwordHash, Role role, boolean mustChangePassword) {
        Long id = jdbc.queryForObject("""
            INSERT INTO app_user (username, password_hash, role, must_change_password)
            VALUES (?, ?, ?, ?) RETURNING id
            """, Long.class, username, passwordHash, role.name(), mustChangePassword);
        if (id == null) throw new IllegalStateException("创建用户失败");
        return id;
    }

    public void updatePassword(long id, String passwordHash, boolean mustChangePassword) {
        jdbc.update("""
            UPDATE app_user SET password_hash = ?, must_change_password = ?, session_version = session_version + 1
            WHERE id = ?
            """, passwordHash, mustChangePassword, id);
    }

    public void updateRoleAndEnabled(long id, Role role, boolean enabled) {
        jdbc.update("""
            UPDATE app_user SET role = ?, enabled = ?, session_version = session_version + 1 WHERE id = ?
            """, role.name(), enabled, id);
    }

    public void recordLogin(long id, Instant at) {
        jdbc.update("UPDATE app_user SET last_login_at = ? WHERE id = ?", Timestamp.from(at), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
}
