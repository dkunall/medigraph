package com.medigraph.service.consent.adapter.out.keys;

import com.medigraph.service.consent.domain.SigningKeyId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The consent-signing Ed25519 key pair, persisted as a PEM file (PKCS#8 private key followed by the X.509 public
 * key) at {@code medigraph.consent.signing-key-path}. If the file is absent a key is generated and written with
 * owner-only permissions; this is a local-development convenience. In production the file is mounted from a secret
 * store and {@code generate-if-missing} is turned off, so a missing mount fails start-up instead of silently minting
 * a new identity.
 */
public final class PemConsentKeyFile {

    public record LoadedKey(SigningKeyId id, KeyPair pair, boolean generated) {
    }

    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private PemConsentKeyFile() {
    }

    public static LoadedKey loadOrCreate(Path path, boolean generateIfMissing) {
        try {
            if (Files.exists(path)) {
                return new LoadedKey(idOf(read(path)), read(path), false);
            }
            if (!generateIfMissing) {
                throw new IllegalStateException("consent signing key not found at " + path + " (generation disabled)");
            }
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            if (write(path, pair)) {
                return new LoadedKey(idOf(pair), pair, true);
            }
            return new LoadedKey(idOf(read(path)), read(path), false); // another replica won the race
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot load consent signing key from " + path, e);
        }
    }

    private static SigningKeyId idOf(KeyPair pair) {
        return SigningKeyId.of(pair.getPublic().getEncoded());
    }

    static KeyPair read(Path path) throws IOException, GeneralSecurityException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        byte[] priv = null;
        byte[] pub = null;
        Matcher m = BLOCK.matcher(pem);
        while (m.find()) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(2));
            switch (m.group(1)) {
                case "PRIVATE KEY" -> priv = der;
                case "PUBLIC KEY" -> pub = der;
                default -> throw new GeneralSecurityException("unexpected PEM block " + m.group(1));
            }
        }
        if (priv == null || pub == null) {
            throw new GeneralSecurityException("PEM must contain a PRIVATE KEY and a PUBLIC KEY block");
        }
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(pub)), kf.generatePrivate(new PKCS8EncodedKeySpec(priv)));
    }

    /** Writes a temp file, then hard-links it into place; returns false if a concurrent writer created the file first. */
    private static boolean write(Path path, KeyPair pair) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".consent-key", ".tmp");
        try {
            restrict(tmp);
            Files.writeString(tmp, block("PRIVATE KEY", pair.getPrivate().getEncoded())
                    + block("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
            try {
                Files.createLink(path, tmp); // atomic and never overwrites, unlike rename
            } catch (UnsupportedOperationException e) {
                Files.move(tmp, path); // no hard links: still refuses to replace an existing file
            }
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void restrict(Path p) throws IOException {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem: rely on the directory's ACLs
        }
    }

    private static String block(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }
}
