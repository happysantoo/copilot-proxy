package com.copiproxy.repo;

import com.copiproxy.model.ApiKeyRecord;
import com.copiproxy.model.CopilotMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {
    private final JdbcTemplate jdbcTemplate;

    public ApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ApiKeyRecord create(String name, String key) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO api_keys(id, name, key, created_at, usage_count, is_default) VALUES (?, ?, ?, ?, 0, 0)",
                id, name, key, now
        );
        return findById(id).orElseThrow();
    }

    public List<ApiKeyRecord> findAll() {
        return jdbcTemplate.query("SELECT * FROM api_keys ORDER BY created_at DESC", mapper());
    }

    public Optional<ApiKeyRecord> findById(String id) {
        List<ApiKeyRecord> rows = jdbcTemplate.query("SELECT * FROM api_keys WHERE id = ?", mapper(), id);
        return rows.stream().findFirst();
    }

    public Optional<ApiKeyRecord> findDefault() {
        List<ApiKeyRecord> rows = jdbcTemplate.query("SELECT * FROM api_keys WHERE is_default = 1 LIMIT 1", mapper());
        return rows.stream().findFirst();
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM api_keys WHERE id = ?", id);
    }

    public void updateName(String id, String name) {
        jdbcTemplate.update("UPDATE api_keys SET name = ? WHERE id = ?", name, id);
    }

    public void setDefault(String id) {
        jdbcTemplate.update("UPDATE api_keys SET is_default = 0 WHERE is_default = 1");
        jdbcTemplate.update("UPDATE api_keys SET is_default = 1 WHERE id = ?", id);
    }

    public void upsertMeta(String id, CopilotMeta meta) {
        jdbcTemplate.update("""
                UPDATE api_keys
                SET token = ?, token_expires_at = ?, reset_time = ?, chat_quota = ?, completions_quota = ?
                WHERE id = ?
                """, meta.token(), meta.expiresAt(), meta.resetTime(), meta.chatQuota(), meta.completionsQuota(), id);
    }

    private RowMapper<ApiKeyRecord> mapper() {
        return (rs, rowNum) -> toRecord(rs);
    }

    private ApiKeyRecord toRecord(ResultSet rs) throws SQLException {
        Long lastUsed = nullableLong(rs, "last_used");
        Long tokenExpiresAt = nullableLong(rs, "token_expires_at");
        Long resetTime = nullableLong(rs, "reset_time");
        Integer chatQuota = nullableInt(rs, "chat_quota");
        Integer completionsQuota = nullableInt(rs, "completions_quota");

        CopilotMeta meta = null;
        String token = rs.getString("token");
        if (token != null && tokenExpiresAt != null) {
            meta = new CopilotMeta(token, tokenExpiresAt, resetTime, chatQuota, completionsQuota);
        }

        return new ApiKeyRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("key"),
                rs.getInt("is_default") == 1,
                rs.getLong("created_at"),
                lastUsed,
                rs.getInt("usage_count"),
                meta
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
