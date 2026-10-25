package com.medigraph.service.ledger.adapter.in.web;

import com.medigraph.common.error.NotFoundException;
import com.medigraph.ledger.chain.InclusionProof;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.ledger.chain.VerificationResult;
import com.medigraph.service.ledger.application.LedgerReplica;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Service-to-service API used by the other services' outbox relays (SERVICE role only). */
@Tag(name = "ledger-internal")
@Validated
@RestController
@RequestMapping("/internal/ledger")
public class LedgerInternalController {

    private final LedgerReplica replica;
    private final Duration commitTimeout;

    public LedgerInternalController(LedgerReplica replica, LedgerWebSettings settings) {
        this.replica = replica;
        this.commitTimeout = settings.commitTimeout();
    }

    @Operation(summary = "Append entries; returns after they are committed by a Raft majority and durably applied")
    @PostMapping("/entries")
    public LedgerReplica.SubmitResult submit(@RequestBody @Size(min = 1, max = 500) List<LedgerEntry> entries)
            throws InterruptedException {
        return replica.submit(entries, commitTimeout);
    }

    @GetMapping("/entries/{entryId}/verification")
    public VerificationResult verify(@PathVariable String entryId,
                                     @RequestParam @Pattern(regexp = "[0-9a-f]{64}") String contentHash) {
        return replica.verifyContentHash(entryId, contentHash);
    }

    @GetMapping("/entries/{entryId}/proof")
    public InclusionProof proof(@PathVariable String entryId) {
        return replica.proof(entryId).orElseThrow(() -> new NotFoundException("ledger entry " + entryId));
    }

    @GetMapping("/status")
    public LedgerReplica.Status status() {
        return replica.status();
    }
}
