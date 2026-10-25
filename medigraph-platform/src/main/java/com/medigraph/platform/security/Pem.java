package com.medigraph.platform.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** PEM (SubjectPublicKeyInfo) helpers for RSA public keys. */
public final class Pem {

    private Pem() {
    }

    public static RSAPublicKey parseRsaPublicKey(String pem) {
        String b64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("medigraph.security.jwt.public-key-pem is not an RSA public key", e);
        }
    }

    public static String encodePublicKey(java.security.PublicKey key) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }
}
