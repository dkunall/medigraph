package com.medigraph.service.gateway.adapter.in.web;

import com.medigraph.common.security.Role;
import com.medigraph.platform.security.dev.DevIssuer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * LOCAL DEVELOPMENT ONLY (registered only when {@code medigraph.gateway.dev-issuer.enabled=true}): an in-memory
 * RS256 issuer so the stack runs without an IdP. {@code /oauth2/token} is the client-credentials grant used by
 * services; {@code /dev/token} mints a user token for manual testing. In production this bean is absent and a
 * real IdP's JWKS is configured instead.
 */
@RestController
@ConditionalOnProperty(prefix = "medigraph.gateway.dev-issuer", name = "enabled", havingValue = "true")
public class DevIssuerController {

    /** Request for a user token. SERVICE is never mintable here; services use client credentials. */
    public record UserTokenRequest(@Pattern(regexp = "[A-Za-z0-9_.@:-]{1,64}") String subject,
                                   @NotEmpty @Size(max = 6) List<Role> roles,
                                   @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String facility) {
    }

    private final DevIssuer issuer;

    public DevIssuerController(DevIssuer issuer) {
        this.issuer = issuer;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return issuer.minter().jwks();
    }

    @PostMapping("/oauth2/token")
    public ResponseEntity<Map<String, Object>> token(@RequestParam("grant_type") String grantType,
                                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!"client_credentials".equals(grantType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
        }
        return issuer.clientCredentials(auth).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "invalid_client")));
    }

    @PostMapping("/dev/token")
    public Map<String, Object> userToken(@Valid @RequestBody UserTokenRequest req) {
        if (req.subject() == null || req.roles().contains(Role.SERVICE)) {
            throw new IllegalArgumentException("subject required; SERVICE tokens come from /oauth2/token");
        }
        return issuer.userToken(req.subject(), req.roles(), req.facility());
    }
}
