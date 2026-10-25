package com.medigraph.crypto;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe in-memory store for tests and the single-process demo. */
public final class InMemoryWrappedKeyStore implements WrappedKeyStore {

    private final ConcurrentMap<String, WrappedKey> keys = new ConcurrentHashMap<>();
    private final Set<String> shredded = ConcurrentHashMap.newKeySet();

    @Override
    public WrappedKey putIfAbsent(WrappedKey key) {
        WrappedKey prev = keys.putIfAbsent(key.patientId(), key);
        return prev == null ? key : prev;
    }

    @Override
    public Optional<WrappedKey> find(String patientId) {
        return Optional.ofNullable(keys.get(patientId));
    }

    @Override
    public boolean replaceWrapping(WrappedKey key) {
        return keys.computeIfPresent(key.patientId(), (id, old) -> key) == key;
    }

    @Override
    public boolean delete(String patientId) {
        WrappedKey k = keys.remove(patientId);
        if (k == null) {
            return false;
        }
        Arrays.fill(k.wrapped(), (byte) 0);
        shredded.add(patientId);
        return true;
    }

    @Override
    public boolean isShredded(String patientId) {
        return shredded.contains(patientId);
    }
}
