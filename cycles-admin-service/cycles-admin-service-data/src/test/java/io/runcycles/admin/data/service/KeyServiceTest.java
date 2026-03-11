package io.runcycles.admin.data.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyServiceTest {

    private KeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = new KeyService();
    }

    @Test
    void generateKeySecret_returnsKeyWithPrefix() {
        String key = keyService.generateKeySecret("cyc_live");
        assertThat(key).startsWith("cyc_live_");
        assertThat(key.length()).isGreaterThan(10);
    }

    @Test
    void generateKeySecret_returnsDifferentKeysEachTime() {
        String key1 = keyService.generateKeySecret("cyc_live");
        String key2 = keyService.generateKeySecret("cyc_live");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void hashKey_returnsValidBCryptHash() {
        String key = keyService.generateKeySecret("cyc_live");
        String hash = keyService.hashKey(key);
        assertThat(hash).startsWith("$2a$12$");
    }

    @Test
    void hashKey_producesDifferentHashForSameKey() {
        String key = keyService.generateKeySecret("cyc_live");
        String hash1 = keyService.hashKey(key);
        String hash2 = keyService.hashKey(key);
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void verifyKey_returnsTrueForCorrectKey() {
        String key = keyService.generateKeySecret("cyc_live");
        String hash = keyService.hashKey(key);
        assertThat(keyService.verifyKey(key, hash)).isTrue();
    }

    @Test
    void verifyKey_returnsFalseForWrongKey() {
        String key = keyService.generateKeySecret("cyc_live");
        String hash = keyService.hashKey(key);
        assertThat(keyService.verifyKey("wrong_key", hash)).isFalse();
    }

    @Test
    void verifyKey_returnsFalseForInvalidHash() {
        assertThat(keyService.verifyKey("some_key", "not_a_hash")).isFalse();
    }

    @Test
    void extractPrefix_returnsPortionBeforeAndAfterUnderscore() {
        String key = keyService.generateKeySecret("cyc_live");
        String prefix = keyService.extractPrefix(key);
        // prefix is substring(0, min(lastIndexOf('_') + 6, key.length))
        assertThat(prefix).startsWith("cyc_live_");
        assertThat(prefix).hasSize(14); // "cyc_live_" (9) + 5 chars from random part
    }

    @Test
    void extractPrefix_handlesKeyWithoutUnderscore() {
        String prefix = keyService.extractPrefix("abcdefghijklmnopqrstuvwxyz");
        // No underscore → substring(0, min(10, length))
        assertThat(prefix).hasSize(10);
    }

    @Test
    void extractPrefix_handlesShortKeyWithoutUnderscore() {
        String prefix = keyService.extractPrefix("short");
        assertThat(prefix).isEqualTo("short");
    }
}
