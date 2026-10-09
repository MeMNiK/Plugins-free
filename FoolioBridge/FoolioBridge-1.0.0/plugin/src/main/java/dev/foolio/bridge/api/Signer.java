package dev.foolio.bridge.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * HMAC-SHA256 signing used by the FoolioBridge protocol (v1).
 *
 * request  signature = hex(HMAC(secret, "foolio-v1:req:" + ts + ":" + nonce + ":" + body))
 * response signature = hex(HMAC(secret, "foolio-v1:res:" + ts + ":" + nonce + ":" + body))
 *
 * ts is unix time in seconds, nonce is the request's random hex nonce (echoed for the response).
 */
public final class Signer {

    public static final String REQ = "req";
    public static final String RES = "res";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] key;

    public Signer(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("secret must be at least 16 characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String kind, long ts, String nonce, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String canonical = "foolio-v1:" + kind + ":" + ts + ":" + nonce + ":" + body;
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public boolean verify(String kind, long ts, String nonce, String body, String signature) {
        if (signature == null) return false;
        byte[] expected = sign(kind, ts, nonce, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int k = 0; k < b.length; k++) {
            out[k * 2] = HEX[(b[k] >> 4) & 0xF];
            out[k * 2 + 1] = HEX[b[k] & 0xF];
        }
        return new String(out);
    }
}
