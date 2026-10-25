package com.medigraph.ledger.chain;

import com.medigraph.ledger.merkle.MerkleProof;

/** Everything a verifier needs to check that an entry is in block {@code blockHeight} of the chain. */
public record InclusionProof(String entryId, long blockHeight, String blockHash, String merkleRoot,
                             MerkleProof merkleProof) {
}
