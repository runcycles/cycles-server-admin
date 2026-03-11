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
    public String extractPrefix(String keySecret) {
        int idx = keySecret.lastIndexOf('_');
        return idx > 0 ? keySecret.substring(0, Math.min(idx + 6, keySecret.length())) : keySecret.substring(0, Math.min(10, keySecret.length()));
    }
}
