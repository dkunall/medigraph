package com.medigraph.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Hashes are carried as lowercase hex strings at API/ledger boundaries. */
public final class Sha256 {

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    public static byte[] digest(byte[] data) {
        return md().digest(data);
    }

    /** Hash of the concatenation of the given byte arrays (no separator). */
    public static byte[] digest(byte[]... parts) {
        MessageDigest md = md();
        for (byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    public static String hex(byte[] data) {
        return HEX.formatHex(digest(data));
    }

    public static String hex(String utf8) {
        return hex(utf8.getBytes(StandardCharsets.UTF_8));
    }

    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] fromHex(String hex) {
        return HEX.parseHex(hex);
    }

    private static MessageDigest md() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
