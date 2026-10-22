package com.modelgateway.auth;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class ClientKeyRepository {

    private static final RowMapper<ClientKey> MAPPER =
            (rs, rowNum) ->
                    new ClientKey(
                            rs.getString("id"),
                            rs.getString("key_prefix"),
                            rs.getString("team"),
                            rs.getString("project"),
                            Arrays.stream(rs.getString("allowed_routes").split(","))
                                    .map(String::trim)
                                    .filter(route -> !route.isEmpty())
                                    .collect(Collectors.toSet()),
                            rs.getBoolean("allow_overseas"),
                            rs.getInt("rpm"),
                            ClientKey.Status.valueOf(rs.getString("status")),
                            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ClientKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ClientKey key, String keyHash) {
        jdbcTemplate.update(
                """
                INSERT INTO gw_client_key
                    (id, key_hash, key_prefix, team, project, allowed_routes, allow_overseas, rpm, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                key.id(),
                keyHash,
                key.keyPrefix(),
                key.team(),
                key.project(),
                String.join(",", key.allowedRoutes().stream().sorted().toList()),
                key.allowOverseas(),
                key.rpm(),
                key.status().name(),
                Timestamp.from(key.createdAt()));
    }

    public Optional<ClientKey> findByHash(String keyHash) {
        return jdbcTemplate.query("SELECT * FROM gw_client_key WHERE key_hash = ?", MAPPER, keyHash).stream().findFirst();
    }

    public List<ClientKey> findAll() {
        return jdbcTemplate.query("SELECT * FROM gw_client_key ORDER BY created_at, id", MAPPER);
    }

    public boolean revoke(String id) {
        return jdbcTemplate.update("UPDATE gw_client_key SET status = 'REVOKED' WHERE id = ? AND status = 'ACTIVE'", id) == 1;
    }
}
