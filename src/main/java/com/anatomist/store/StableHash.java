package com.anatomist.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Canonical truncated SHA-256 keys used by persisted incremental identities. */
final class StableHash {
    private static final int FACT_BYTES = 16;
    private static final int DEPENDENCY_BYTES = 8;
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    });

    private StableHash() {}

    static byte[] text(String value) {
        return digest(value, DEPENDENCY_BYTES);
    }

    static byte[] values(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value);
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        return digest(canonical.toString(), FACT_BYTES);
    }

    private static byte[] digest(String value, int bytes) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        byte[] full = digest.digest((value == null ? "" : value)
                .getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(full, bytes);
    }

}
