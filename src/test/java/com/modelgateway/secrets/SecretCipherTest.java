package com.modelgateway.secrets;

import static org.junit.jupiter.api.Assertions.*;

import com.modelgateway.secrets.SecretCipher.Sealed;
import com.modelgateway.support.TestConfigs;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String V2 = "v2:" + Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void shouldRoundTripWithoutLeakingPlaintext() {
        SecretCipher cipher = new SecretCipher(TestConfigs.masterKeys(), "v1");

        Sealed sealed = cipher.seal("sk-live-123", "provider-key:dashscope");

        assertEquals("v1", sealed.version());
        assertFalse(sealed.ciphertext().contains("sk-live"));
        assertNotEquals(sealed.ciphertext(), cipher.seal("sk-live-123", "provider-key:dashscope").ciphertext(),
                "fresh IV per seal");
        assertEquals("sk-live-123", cipher.open(sealed, "provider-key:dashscope"));
    }

    @Test
    void ciphertextShouldNotOpenUnderAnotherProvider() {
        SecretCipher cipher = new SecretCipher(TestConfigs.masterKeys(), "v1");
        Sealed sealed = cipher.seal("sk-live-123", "provider-key:dashscope");

        assertThrows(IllegalStateException.class, () -> cipher.open(sealed, "provider-key:openai"));
    }

    @Test
    void tamperedCiphertextShouldFailAuthentication() {
        SecretCipher cipher = new SecretCipher(TestConfigs.masterKeys(), "v1");
        byte[] packed = Base64.getDecoder().decode(cipher.seal("sk-live-123", "ctx").ciphertext());
        packed[packed.length - 1] ^= 1;

        assertThrows(
                IllegalStateException.class,
                () -> cipher.open(new Sealed("v1", Base64.getEncoder().encodeToString(packed)), "ctx"));
    }

    @Test
    void rotatedCipherShouldStillOpenOldVersions() {
        Sealed oldSeal = new SecretCipher(TestConfigs.masterKeys(), "v1").seal("sk-old", "ctx");
        SecretCipher rotated = new SecretCipher(TestConfigs.masterKeys() + "," + V2, "v2");

        assertEquals("sk-old", rotated.open(oldSeal, "ctx"));
        assertEquals("v2", rotated.seal("sk-new", "ctx").version());
    }

    @Test
    void shouldRefuseToStartWithoutTheCurrentVersion() {
        assertThrows(IllegalStateException.class, () -> new SecretCipher(TestConfigs.masterKeys(), "v9"));
        assertThrows(IllegalStateException.class, () -> new SecretCipher("", "v1"));
        assertEquals(8, SecretCipher.fingerprint("sk-live-123").length());
    }
}
