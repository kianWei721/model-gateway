package com.modelgateway.providerkeys;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class ProviderKeyRepository {

    private static final RowMapper<ProviderKey> MAPPER =
            (rs, rowNum) ->
                    new ProviderKey(
                            rs.getString("id"),
                            rs.getString("provider"),
                            rs.getString("secret_ciphertext"),
                            rs.getString("master_key_version"),
                            rs.getString("fingerprint"),
                            ProviderKey.Status.valueOf(rs.getString("status")),
                            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ProviderKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ProviderKey key) {
        jdbcTemplate.update(
                """
                INSERT INTO gw_provider_key
                    (id, provider, secret_ciphertext, master_key_version, fingerprint, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                key.id(),
                key.provider(),
                key.ciphertext(),
                key.masterKeyVersion(),
                key.fingerprint(),
                key.status().name(),
                Timestamp.from(key.createdAt()));
    }

    public List<ProviderKey> findActive() {
        return jdbcTemplate.query(
                "SELECT * FROM gw_provider_key WHERE status = 'ACTIVE' ORDER BY created_at, id", MAPPER);
    }

    public List<ProviderKey> findAll() {
        return jdbcTemplate.query("SELECT * FROM gw_provider_key ORDER BY created_at, id", MAPPER);
    }

    public boolean disable(String id, Instant at) {
        return jdbcTemplate.update(
                        "UPDATE gw_provider_key SET status = 'DISABLED', disabled_at = ? WHERE id = ? AND status = 'ACTIVE'",
                        Timestamp.from(at),
                        id)
                == 1;
    }

    public void reseal(String id, String ciphertext, String masterKeyVersion) {
        jdbcTemplate.update(
                "UPDATE gw_provider_key SET secret_ciphertext = ?, master_key_version = ? WHERE id = ?",
                ciphertext,
                masterKeyVersion,
                id);
    }
}
