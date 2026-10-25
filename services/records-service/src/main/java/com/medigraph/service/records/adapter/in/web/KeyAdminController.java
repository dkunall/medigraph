package com.medigraph.service.records.adapter.in.web;

import com.medigraph.service.records.application.KeyRotation;
import com.medigraph.service.records.application.KeyRotation.RotationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "key-management")
@RestController
public class KeyAdminController {

    private final KeyRotation rotation;

    public KeyAdminController(KeyRotation rotation) {
        this.rotation = rotation;
    }

    @Operation(summary = "After adding a new current KEK: rewrap every DEK still under an old KEK (records untouched)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/admin/keys/rewrap")
    public RotationReport rewrap() {
        return rotation.rewrapAll(500);
    }
}
