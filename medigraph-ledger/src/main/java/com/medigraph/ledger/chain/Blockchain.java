package com.medigraph.ledger.chain;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.ledger.merkle.MerkleProof;
import com.medigraph.ledger.merkle.MerkleTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only hash chain of {@link Block}s with an entry index for O(1) proof lookup. One instance per ledger
 * node; Raft guarantees every node appends the same blocks in the same order.
 */
public final class Blockchain {

    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, int[]> entryIndex = new HashMap<>(); // entryId -> {blockHeight, position}

    /**
     * Rebuilds a chain from persisted blocks (restart recovery). The blocks are re-verified first, so a row edited
     * at rest is detected at boot instead of being silently trusted. O(total entries).
     *
     * @throws IllegalStateException if the stored blocks do not form a valid chain
     */
    public static Blockchain restore(List<Block> stored) {
        Blockchain c = new Blockchain();
        for (Block b : stored) {
            c.blocks.add(b);
            for (int i = 0; i < b.entries().size(); i++) {
                c.entryIndex.put(b.entries().get(i).entryId(), new int[]{(int) b.height(), i});
            }
        }
        VerificationResult v = c.verifyChain();
        if (!v.valid()) {
            throw new IllegalStateException("persisted ledger failed verification: " + v.reason());
        }
        return c;
    }

    public synchronized Block append(long timestampMillis, List<LedgerEntry> entries) {
        long height = blocks.size();
        String prev = blocks.isEmpty() ? Block.GENESIS_PREVIOUS : blocks.get(blocks.size() - 1).hash();
        Block b = Block.create(height, prev, timestampMillis, entries);
        blocks.add(b);
        for (int i = 0; i < b.entries().size(); i++) {
            entryIndex.put(b.entries().get(i).entryId(), new int[]{(int) height, i});
        }
        return b;
    }

    public synchronized int size() {
        return blocks.size();
    }

    public synchronized Optional<Block> tip() {
        return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks.get(blocks.size() - 1));
    }

    public synchronized Block block(long height) {
        return blocks.get((int) height);
    }

    public synchronized List<Block> blocks() {
        return List.copyOf(blocks);
    }

    public synchronized Optional<LedgerEntry> entry(String entryId) {
        int[] loc = entryIndex.get(entryId);
        return loc == null ? Optional.empty() : Optional.of(blocks.get(loc[0]).entries().get(loc[1]));
    }

    public synchronized Optional<InclusionProof> proof(String entryId) {
        int[] loc = entryIndex.get(entryId);
        if (loc == null) {
            return Optional.empty();
        }
        Block b = blocks.get(loc[0]);
        MerkleProof mp = Block.tree(b.entries()).proof(loc[1]);
        return Optional.of(new InclusionProof(entryId, b.height(), b.hash(), b.merkleRoot(), mp));
    }

    /** Full audit: every block self-consistent and linked to its predecessor. O(total entries). */
    public synchronized VerificationResult verifyChain() {
        String prev = Block.GENESIS_PREVIOUS;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.height() != i) {
                return VerificationResult.fail("block " + i + " has wrong height " + b.height());
            }
            if (!b.previousHash().equals(prev)) {
                return VerificationResult.fail("block " + i + " does not link to block " + (i - 1));
            }
            if (!b.isInternallyConsistent()) {
                return VerificationResult.fail("block " + i + " content does not match its Merkle root/hash");
            }
            prev = b.hash();
        }
        return VerificationResult.ok();
    }

    /**
     * Verifies off-chain data against the chain: the presented bytes must hash to the entry's {@code contentHash},
     * the entry must be included under its block's Merkle root, and the block must be part of a valid chain.
     */
    public synchronized VerificationResult verifyRecord(String entryId, byte[] presentedContent) {
        Optional<LedgerEntry> e = entry(entryId);
        if (e.isEmpty()) {
            return VerificationResult.fail("entry not on ledger");
        }
        if (e.get().contentHash() == null) {
            return VerificationResult.fail("entry has no content hash");
        }
        if (!Sha256.hex(presentedContent).equals(e.get().contentHash())) {
            return VerificationResult.fail("record hash mismatch: content was modified");
        }
        InclusionProof p = proof(entryId).orElseThrow();
        if (!Sha256.toHex(MerkleTree.leafHash(e.get().canonicalBytes())).equals(p.merkleProof().leafHashHex())
                || !MerkleTree.verify(p.merkleProof(), Sha256.fromHex(p.merkleRoot()))) {
            return VerificationResult.fail("Merkle inclusion proof failed");
        }
        return verifyChain();
    }

    /**
     * Like {@link #verifyRecord} but for a verifier that holds only the SHA-256 of the content (e.g. a remote
     * service that decrypted the record itself and must not ship plaintext to the ledger).
     */
    public synchronized VerificationResult verifyContentHash(String entryId, String contentHashHex) {
        Optional<LedgerEntry> e = entry(entryId);
        if (e.isEmpty()) {
            return VerificationResult.fail("entry not on ledger");
        }
        if (e.get().contentHash() == null || !e.get().contentHash().equals(contentHashHex)) {
            return VerificationResult.fail("record hash mismatch: content was modified");
        }
        InclusionProof p = proof(entryId).orElseThrow();
        if (!Sha256.toHex(MerkleTree.leafHash(e.get().canonicalBytes())).equals(p.merkleProof().leafHashHex())
                || !MerkleTree.verify(p.merkleProof(), Sha256.fromHex(p.merkleRoot()))) {
            return VerificationResult.fail("Merkle inclusion proof failed");
        }
        return verifyChain();
    }

    /** Test/demo hook: simulates an attacker editing a stored block in place (bypassing append). */
    public synchronized void tamperForTest(long height, Block replacement) {
        blocks.set((int) height, replacement);
    }
}
