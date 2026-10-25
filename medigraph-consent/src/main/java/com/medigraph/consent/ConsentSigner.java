package com.medigraph.consent;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ed25519 signing of consent artefacts. Verification keys are looked up by {@code signerKeyId}, so keys can rotate
 * while old artefacts stay verifiable. In this build the consent service holds the key (generated at start-up);
 * patient-held keys / SD-JWT are on the roadmap.
 */
public final class ConsentSigner {

    private final String keyId;
    private final KeyPair keyPair;
    private final Map<String, PublicKey> verificationKeys = new ConcurrentHashMap<>();

    public ConsentSigner(String keyId, KeyPair keyPair) {
        this.keyId = keyId;
        this.keyPair = keyPair;
        verificationKeys.put(keyId, keyPair.getPublic());
    }

    public static ConsentSigner generate(String keyId) {
        try {
            return new ConsentSigner(keyId, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is available in every JDK 15+", e);
        }
    }

    public ConsentArtefact sign(ConsentArtefact unsigned) {
        ConsentArtefact withKey = unsigned.withSignature(keyId, null);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(keyPair.getPrivate());
            s.update(withKey.signingPayload());
            return withKey.withSignature(keyId, Base64.getEncoder().encodeToString(s.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    public boolean verify(ConsentArtefact a) {
        PublicKey pk = a.signerKeyId() == null ? null : verificationKeys.get(a.signerKeyId());
        if (pk == null || a.signature() == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pk);
            s.update(a.signingPayload());
            return s.verify(Base64.getDecoder().decode(a.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public void trust(String otherKeyId, PublicKey key) {
        verificationKeys.put(otherKeyId, key);
    }

    public String keyId() {
        return keyId;
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }
}
