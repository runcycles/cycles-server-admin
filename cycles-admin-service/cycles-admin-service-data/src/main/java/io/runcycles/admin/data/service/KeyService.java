package io.runcycles.admin.data.service;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.Base64;
@Service
public class KeyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    public String generateKeySecret(String prefix) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String hashKey(String keySecret) {
        return BCrypt.hashpw(keySecret, BCrypt.gensalt(12));
    }
    public boolean verifyKey(String keySecret, String hash) {
        try {
            return BCrypt.checkpw(keySecret, hash);
        } catch (Exception e) {
            return false;
        }
    }
    private static final int PREFIX_LENGTH = 14; // "cyc_live_" (9) + 5 chars from random part
    public String extractPrefix(String keySecret) {
        return keySecret.substring(0, Math.min(PREFIX_LENGTH, keySecret.length()));
    }
}
