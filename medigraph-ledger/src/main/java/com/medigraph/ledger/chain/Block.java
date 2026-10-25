package com.medigraph.ledger.chain;

import com.medigraph.common.crypto.Sha256;
import com.medigraph.ledger.merkle.MerkleTree;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A sealed batch of ledger entries. The header commits to the entries via {@code merkleRoot} and to all history via
 * {@code previousHash}; {@code hash = SHA-256(height|previousHash|merkleRoot|timestamp|count)}.
 * Changing any entry changes its leaf, the root, this block's hash, and every later block's {@code previousHash}.
 */
public record Block(long height, String previousHash, String merkleRoot, long timestampMillis,
                    List<LedgerEntry> entries, String hash) {

    public static final String GENESIS_PREVIOUS = "0".repeat(64);

    public static Block create(long height, String previousHash, long timestampMillis, List<LedgerEntry> entries) {
        List<LedgerEntry> copy = List.copyOf(entries);
        String root = merkleRootOf(copy);
        return new Block(height, previousHash, root, timestampMillis, copy,
                headerHash(height, previousHash, root, timestampMillis, copy.size()));
    }

    public static String merkleRootOf(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return Sha256.hex(new byte[0]);
        }
        return tree(entries).rootHex();
    }

    public static MerkleTree tree(List<LedgerEntry> entries) {
        return MerkleTree.fromLeafHashes(entries.stream().map(e -> MerkleTree.leafHash(e.canonicalBytes())).toList());
    }

    public static String headerHash(long height, String previousHash, String merkleRoot, long ts, int count) {
        return Sha256.hex((height + "|" + previousHash + "|" + merkleRoot + "|" + ts + "|" + count)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Recomputes root and header hash from content; false means the block was altered after sealing. */
    public boolean isInternallyConsistent() {
        String root = merkleRootOf(entries);
        return root.equals(merkleRoot) && headerHash(height, previousHash, root, timestampMillis, entries.size()).equals(hash);
    }
}
