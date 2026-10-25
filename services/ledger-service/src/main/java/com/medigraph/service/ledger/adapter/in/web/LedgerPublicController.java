package com.medigraph.service.ledger.adapter.in.web;

import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.security.Actor;
import com.medigraph.ledger.chain.Block;
import com.medigraph.ledger.chain.InclusionProof;
import com.medigraph.ledger.chain.LedgerEntry;
import com.medigraph.ledger.chain.VerificationResult;
import com.medigraph.platform.security.CurrentActor;
import com.medigraph.service.ledger.application.LedgerAuditQuery;
import com.medigraph.service.ledger.application.LedgerReplica;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "ledger")
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerPublicController {

    public record BlockView(long height, String hash, String previousHash, String merkleRoot, long timestampMillis,
                            List<String> entryIds) {
        static BlockView of(Block b) {
            return new BlockView(b.height(), b.hash(), b.previousHash(), b.merkleRoot(), b.timestampMillis(),
                    b.entries().stream().map(LedgerEntry::entryId).toList());
        }
    }

    private final LedgerReplica replica;
    private final LedgerAuditQuery audit;

    public LedgerPublicController(LedgerReplica replica, LedgerAuditQuery audit) {
        this.replica = replica;
        this.audit = audit;
    }

    @PreAuthorize("hasAnyRole('STEWARD','ADMIN')")
    @GetMapping("/status")
    public LedgerReplica.Status status() {
        return replica.status();
    }

    @PreAuthorize("hasAnyRole('STEWARD','ADMIN')")
    @GetMapping("/verify")
    public VerificationResult verify() {
        return replica.verifyChain();
    }

    @PreAuthorize("hasAnyRole('STEWARD','ADMIN')")
    @GetMapping("/blocks/{height}")
    public BlockView block(@PathVariable long height) {
        return replica.block(height).map(BlockView::of).orElseThrow(() -> new NotFoundException("block " + height));
    }

    @PreAuthorize("hasAnyRole('STEWARD','ADMIN')")
    @GetMapping("/entries/{entryId}/proof")
    public InclusionProof proof(@PathVariable String entryId) {
        return replica.proof(entryId).orElseThrow(() -> new NotFoundException("ledger entry " + entryId));
    }

    /** "Who touched my data" (the patient themselves, or a steward/admin). */
    @PreAuthorize("hasAnyRole('PATIENT','STEWARD','ADMIN')")
    @GetMapping("/entries")
    public List<LedgerEntry> entries(@CurrentActor Actor actor, @RequestParam String healthId) {
        return audit.entriesAbout(actor, healthId);
    }
}
