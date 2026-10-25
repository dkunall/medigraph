package com.medigraph.service.consent.adapter.in.web;

import com.medigraph.service.consent.application.port.out.SigningKeyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;

@Tag(name = "consent")
@RestController
public class SigningKeyController {

    public record PublicKeyView(String keyId, String algorithm, String x509Base64) {
    }

    private final SigningKeyRegistry keys;

    public SigningKeyController(SigningKeyRegistry keys) {
        this.keys = keys;
    }

    @Operation(summary = "Public consent-signing keys, so anyone holding a consent artefact can verify it offline")
    @GetMapping("/api/v1/consent-keys")
    public List<PublicKeyView> keys() {
        return keys.all().entrySet().stream().map(e -> new PublicKeyView(e.getKey(), e.getValue().getAlgorithm(),
                Base64.getEncoder().encodeToString(e.getValue().getEncoded()))).toList();
    }
}
