package com.modelgateway.providerkeys;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.providerkeys.ProviderKeyPool.LeasedKey;
import com.modelgateway.secrets.SecretCipher;
import com.modelgateway.support.TestConfigs;
import com.modelgateway.support.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProviderKeyPoolTest {

    private final JdbcTemplate jdbc = TestDatabase.migrated();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
    private final ProviderKeyPool pool =
            new ProviderKeyPool(
                    new ProviderKeyRepository(jdbc),
                    new SecretCipher(TestConfigs.masterKeys(), "v1"),
                    clock,
                    Duration.ofMinutes(10));

    @Test
    void storedKeysShouldBeSealedAndRotateRoundRobin() {
        pool.add("dashscope", "sk-a");
        pool.add("dashscope", "sk-b");

        Integer plaintextRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gw_provider_key WHERE secret_ciphertext LIKE '%sk-%'", Integer.class);
        assertEquals(0, plaintextRows);

        Set<String> served = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            served.add(pool.next("dashscope", Set.of()).orElseThrow().secret());
        }
        assertEquals(Set.of("sk-a", "sk-b"), served);
        assertTrue(pool.next("openai", Set.of()).isEmpty());
    }

    @Test
    void unauthorizedKeyShouldCoolDownThenComeBack() {
        pool.add("dashscope", "sk-only");
        LeasedKey key = pool.next("dashscope", Set.of()).orElseThrow();

        pool.coolDown(key);
        assertTrue(pool.next("dashscope", Set.of()).isEmpty());

        clock.advance(Duration.ofMinutes(11));
        assertEquals("sk-only", pool.next("dashscope", Set.of()).orElseThrow().secret());
    }

    @Test
    void disablingShouldTakeEffectWithoutRestart() {
        ProviderKey old = pool.add("dashscope", "sk-old");
        pool.add("dashscope", "sk-new");

        assertTrue(pool.disable(old.id()));

        for (int i = 0; i < 3; i++) {
            assertEquals("sk-new", pool.next("dashscope", Set.of()).orElseThrow().secret());
        }
        assertFalse(pool.disable(old.id()), "already disabled");
    }

    @Test
    void masterKeyRotationShouldResealEveryKey() {
        pool.add("dashscope", "sk-a");
        String v2 = "v2:" + Base64.getEncoder().encodeToString(new byte[32]);
        ProviderKeyPool rotated =
                new ProviderKeyPool(
                        new ProviderKeyRepository(jdbc),
                        new SecretCipher(TestConfigs.masterKeys() + "," + v2, "v2"),
                        clock,
                        Duration.ofMinutes(10));

        assertEquals(1, rotated.resealAll());

        assertEquals("v2", jdbc.queryForObject("SELECT master_key_version FROM gw_provider_key", String.class));
        ProviderKeyPool v2Only =
                new ProviderKeyPool(new ProviderKeyRepository(jdbc), new SecretCipher(v2, "v2"), clock, Duration.ofMinutes(10));
        v2Only.reload();
        assertEquals("sk-a", v2Only.next("dashscope", Set.of()).orElseThrow().secret());
    }

    @Test
    void leasedKeyToStringShouldNotPrintTheSecret() {
        pool.add("dashscope", "sk-very-secret");

        assertFalse(pool.next("dashscope", Set.of()).orElseThrow().toString().contains("sk-very-secret"));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
