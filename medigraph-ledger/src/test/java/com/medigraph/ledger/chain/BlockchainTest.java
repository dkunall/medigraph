package com.medigraph.ledger.chain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.medigraph.ledger.chain.LedgerFixtures.access;
import static com.medigraph.ledger.chain.LedgerFixtures.recordHash;
import static org.assertj.core.api.Assertions.assertThat;

class BlockchainTest {

    @Test
    void blocksAreHashChained() {
        Blockchain c = new Blockchain();
        Block b0 = c.append(1, List.of(recordHash("{a}")));
        Block b1 = c.append(2, List.of(recordHash("{b}"), access("dr-b")));
        assertThat(b0.previousHash()).isEqualTo(Block.GENESIS_PREVIOUS);
        assertThat(b1.previousHash()).isEqualTo(b0.hash());
        assertThat(c.verifyChain().valid()).isTrue();
    }

    @Test
    void untamperedRecordVerifies() {
        Blockchain c = new Blockchain();
        LedgerEntry e = recordHash("{\"resourceType\":\"Condition\",\"code\":\"E11\"}");
        c.append(1, List.of(access("x"), e, access("y")));
        assertThat(c.verifyRecord(e.entryId(), "{\"resourceType\":\"Condition\",\"code\":\"E11\"}".getBytes()).valid()).isTrue();
    }

    @Test
    void tamperedOffChainRecordFailsVerification() {
        Blockchain c = new Blockchain();
        LedgerEntry e = recordHash("{\"code\":\"E11\"}");
        c.append(1, List.of(e));
        VerificationResult r = c.verifyRecord(e.entryId(), "{\"code\":\"E10\"}".getBytes());
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).contains("hash mismatch");
    }

    @Test
    void editingAnEntryInsideASealedBlockBreaksTheChain() {
        Blockchain c = new Blockchain();
        LedgerEntry original = recordHash("{v1}");
        c.append(1, List.of(original, access("a")));
        c.append(2, List.of(access("b")));
        Block b0 = c.block(0);
        // attacker swaps the stored hash for the hash of a forged record but keeps the old header
        List<LedgerEntry> forged = new ArrayList<>(b0.entries());
        LedgerEntry fake = recordHash("{forged}");
        forged.set(0, new LedgerEntry(original.entryId(), original.type(), original.subject(), original.actor(),
                fake.contentHash(), original.timestampMillis(), original.metadata()));
        c.tamperForTest(0, new Block(0, b0.previousHash(), b0.merkleRoot(), b0.timestampMillis(), forged, b0.hash()));

        assertThat(c.verifyChain().valid()).isFalse();
        assertThat(c.verifyRecord(original.entryId(), "{forged}".getBytes()).valid()).isFalse();
    }

    @Test
    void recomputingTheTamperedBlockStillBreaksTheLinkToTheNextBlock() {
        Blockchain c = new Blockchain();
        LedgerEntry original = recordHash("{v1}");
        c.append(1, List.of(original));
        c.append(2, List.of(access("b")));
        // a smarter attacker re-seals block 0 consistently ...
        Block resealed = Block.create(0, Block.GENESIS_PREVIOUS, 1, List.of(recordHash("{forged}")));
        c.tamperForTest(0, resealed);
        VerificationResult r = c.verifyChain();
        // ... but block 1 still points at the old hash
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).contains("does not link");
    }

    @Test
    void proofLookupIsByEntryId() {
        Blockchain c = new Blockchain();
        List<LedgerEntry> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(recordHash("{r" + i + "}"));
        }
        c.append(1, batch);
        InclusionProof p = c.proof(batch.get(57).entryId()).orElseThrow();
        assertThat(p.blockHeight()).isZero();
        assertThat(p.merkleProof().leafIndex()).isEqualTo(57);
        assertThat(p.merkleProof().steps()).hasSizeLessThanOrEqualTo(7);
        assertThat(c.proof("missing")).isEmpty();
    }

    @Test
    void restoreRebuildsTheIndexAndRejectsTamperedStorage() {
        Blockchain c = new Blockchain();
        LedgerEntry e = LedgerFixtures.recordHash("{\"a\":1}");
        c.append(1L, java.util.List.of(e));
        c.append(2L, java.util.List.of(LedgerFixtures.access("dr-b")));
        Blockchain restored = Blockchain.restore(c.blocks());
        org.assertj.core.api.Assertions.assertThat(restored.proof(e.entryId())).isPresent();
        org.assertj.core.api.Assertions.assertThat(restored.verifyContentHash(e.entryId(), e.contentHash()).valid()).isTrue();
        org.assertj.core.api.Assertions.assertThat(restored.verifyContentHash(e.entryId(), "0".repeat(64)).valid()).isFalse();

        java.util.List<Block> edited = new java.util.ArrayList<>(c.blocks());
        Block b0 = edited.get(0);
        edited.set(0, new Block(b0.height(), b0.previousHash(), b0.merkleRoot(), b0.timestampMillis() + 1, b0.entries(), b0.hash()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Blockchain.restore(edited))
                .isInstanceOf(IllegalStateException.class);
    }
}
