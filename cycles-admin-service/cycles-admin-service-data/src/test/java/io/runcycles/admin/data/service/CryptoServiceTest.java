package io.runcycles.admin.data.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptDecrypt_roundTrip() {
        CryptoService service = new CryptoService(generateKey());
        String secret = "whsec_myTestSigningSecret123";

        String encrypted = service.encrypt(secret);
        String decrypted = service.decrypt(encrypted);

        assertThat(encrypted).startsWith("enc:");
        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(decrypted).isEqualTo(secret);
    }

    @Test
    void encrypt_differentIVsProduceDifferentCiphertexts() {
        CryptoService service = new CryptoService(generateKey());
        String secret = "whsec_test";

        String enc1 = service.encrypt(secret);
        String enc2 = service.encrypt(secret);

        assertThat(enc1).isNotEqualTo(enc2); // Random IV each time
        assertThat(service.decrypt(enc1)).isEqualTo(secret);
        assertThat(service.decrypt(enc2)).isEqualTo(secret);
    }

    @Test
    void passThrough_whenNoKey() {
        CryptoService service = new CryptoService("");
        String secret = "whsec_plaintext";

        assertThat(service.encrypt(secret)).isEqualTo(secret);
        assertThat(service.decrypt(secret)).isEqualTo(secret);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void passThrough_nullValues() {
        CryptoService service = new CryptoService(generateKey());

        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void decrypt_plaintextBackwardCompat() {
        CryptoService service = new CryptoService(generateKey());
        // Existing plaintext secret without "enc:" prefix
        String plaintext = "whsec_oldPlaintextSecret";

        assertThat(service.decrypt(plaintext)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_encryptedWithNoKey_returnsRaw() {
        CryptoService noKey = new CryptoService("");
        // Simulate an encrypted value read when no key is configured
        String encrypted = "enc:someBase64Data";

        assertThat(noKey.decrypt(encrypted)).isEqualTo(encrypted);
    }

    @Test
    void constructor_invalidKeyLength_throws() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 16 bytes, need 32
        assertThatThrownBy(() -> new CryptoService(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void isEnabled_trueWithKey() {
        CryptoService service = new CryptoService(generateKey());
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void decrypt_wrongKey_throws() {
        CryptoService encryptor = new CryptoService(generateKey());
        CryptoService wrongDecryptor = new CryptoService(generateKey());

        String encrypted = encryptor.encrypt("secret");
        assertThatThrownBy(() -> wrongDecryptor.decrypt(encrypted))
                .isInstanceOf(RuntimeException.class);
    }
}
