package com.medigraph.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Factories for the dev/self-hosted {@link LocalAesKekProvider}. Key material is base64 of exactly 32 bytes.
 *
 * <ul>
 *   <li>{@link #fromEnvironment}: variables {@code MEDIGRAPH_KEK_<ID>=<base64>} plus {@code MEDIGRAPH_KEK_CURRENT=<ID>};
 *       ids are lower-cased. Typical for containers that get secrets injected as env vars.</li>
 *   <li>{@link #fromDirectory}: one file per key, file name = key id, content = base64 (e.g. a Kubernetes secret
 *       volume); the current id is passed explicitly.</li>
 * </ul>
 * Nothing here generates or persists keys: a missing KEK is a configuration error the caller decides how to handle.
 */
public final class KekProviders {

    public static final String ENV_PREFIX = "MEDIGRAPH_KEK_";
    public static final String ENV_CURRENT = "MEDIGRAPH_KEK_CURRENT";

    private KekProviders() {
    }

    public static LocalAesKekProvider fromEnvironment(Map<String, String> env) {
        Map<String, byte[]> keys = new TreeMap<>();
        env.forEach((name, value) -> {
            if (name.startsWith(ENV_PREFIX) && !name.equals(ENV_CURRENT) && value != null && !value.isBlank()) {
                keys.put(name.substring(ENV_PREFIX.length()).toLowerCase(), decode(name, value));
            }
        });
        String current = env.get(ENV_CURRENT);
        if (current == null || current.isBlank()) {
            throw new IllegalStateException(ENV_CURRENT + " must name the active KEK");
        }
        return new LocalAesKekProvider(keys, current.toLowerCase());
    }

    public static LocalAesKekProvider fromDirectory(Path dir, String currentKekId) {
        Map<String, byte[]> keys = new TreeMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, Files::isRegularFile)) {
            for (Path f : files) {
                keys.put(f.getFileName().toString(), decode(f.toString(), Files.readString(f).strip()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read KEK directory " + dir, e);
        }
        return new LocalAesKekProvider(keys, currentKekId);
    }

    /** Single inline base64 key (dev profile). */
    public static LocalAesKekProvider fromBase64(String kekId, String base64) {
        return new LocalAesKekProvider(Map.of(kekId, decode(kekId, base64)), kekId);
    }

    static byte[] decode(String source, String base64) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("KEK " + source + " is not valid base64", e);
        }
    }
}
