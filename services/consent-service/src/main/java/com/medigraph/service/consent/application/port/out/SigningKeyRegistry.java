package com.medigraph.service.consent.application.port.out;

import java.security.PublicKey;
import java.util.Map;

/** Public halves of every signing key ever used, so artefacts signed before a key rotation keep verifying. */
public interface SigningKeyRegistry {

    void register(String keyId, PublicKey key);

    Map<String, PublicKey> all();
}
