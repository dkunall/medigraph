package com.medigraph.ledger.merkle;

import java.util.List;

/**
 * Audit path from a leaf to the root. {@code siblingOnLeft} tells the verifier the concatenation order.
 * Hex strings keep the proof JSON-friendly so a third party can verify it with any SHA-256 implementation.
 *
 * <p>Complexity: proof size and verification are O(log n) hashes for a tree of n leaves.
 */
public record MerkleProof(int leafIndex, int leafCount, String leafHashHex, List<Step> steps) {

    public record Step(String siblingHex, boolean siblingOnLeft) {
    }
}
