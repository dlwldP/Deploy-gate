package deploygate.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Hashing and generation for API tokens.
 *
 * <p>Tokens are high-entropy random strings, so a single SHA-256 pass is enough —
 * unlike user-chosen passwords they are not vulnerable to dictionary attacks, and a
 * deliberately slow KDF would only add latency to every request.
 */
public final class ApiTokens {

    private static final String PREFIX = "dgt_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiTokens() {
    }

    /** Generates a new plaintext token. Only ever shown once — store the hash. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Constant-time comparison, to keep token checks free of timing side channels. */
    public static boolean matches(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
