package com.medigraph.ledger.merkle;

import com.medigraph.common.crypto.Sha256;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Binary Merkle tree over SHA-256 with RFC 6962-style domain separation:
 * {@code leaf = H(0x00 || data)}, {@code node = H(0x01 || left || right)}. The prefixes stop an attacker presenting an
 * internal node as a leaf (second-preimage). An odd node at the end of a level is <i>promoted</i> unchanged instead of
 * being paired with a copy of itself, which avoids the duplicate-leaf ambiguity of Bitcoin's tree (CVE-2012-2459).
 *
 * <p>All levels are kept, so proof generation is O(log n) after an O(n) build.
 */
public final class MerkleTree {

    private static final byte[] LEAF_PREFIX = {0x00};
    private static final byte[] NODE_PREFIX = {0x01};

    private final List<byte[][]> levels; // levels.get(0) = leaf hashes, last = {root}

    private MerkleTree(List<byte[][]> levels) {
        this.levels = levels;
    }

    public static byte[] leafHash(byte[] data) {
        return Sha256.digest(LEAF_PREFIX, data);
    }

    public static byte[] nodeHash(byte[] left, byte[] right) {
        return Sha256.digest(NODE_PREFIX, left, right);
    }

    /** Builds from already-hashed leaves (see {@link #leafHash}). */
    public static MerkleTree fromLeafHashes(List<byte[]> leafHashes) {
        if (leafHashes.isEmpty()) {
            throw new IllegalArgumentException("a Merkle tree needs at least one leaf");
        }
        List<byte[][]> levels = new ArrayList<>();
        byte[][] level = leafHashes.stream().map(byte[]::clone).toArray(byte[][]::new);
        levels.add(level);
        while (level.length > 1) {
            byte[][] next = new byte[(level.length + 1) / 2][];
            for (int i = 0; i < level.length; i += 2) {
                next[i / 2] = i + 1 < level.length ? nodeHash(level[i], level[i + 1]) : level[i];
            }
            levels.add(next);
            level = next;
        }
        return new MerkleTree(levels);
    }

    public byte[] root() {
        return levels.get(levels.size() - 1)[0].clone();
    }

    public String rootHex() {
        return Sha256.toHex(root());
    }

    public int leafCount() {
        return levels.get(0).length;
    }

    public int height() {
        return levels.size() - 1;
    }

    /** Inclusion proof for leaf {@code index}: at most {@code ceil(log2 n)} sibling hashes. */
    public MerkleProof proof(int index) {
        if (index < 0 || index >= leafCount()) {
            throw new IndexOutOfBoundsException("leaf " + index + " of " + leafCount());
        }
        List<MerkleProof.Step> steps = new ArrayList<>();
        int i = index;
        for (int l = 0; l < levels.size() - 1; l++) {
            byte[][] level = levels.get(l);
            boolean isRight = (i & 1) == 1;
            int sibling = isRight ? i - 1 : i + 1;
            if (sibling < level.length) {
                steps.add(new MerkleProof.Step(Sha256.toHex(level[sibling]), isRight));
            } // else: promoted node, no sibling at this level
            i /= 2;
        }
        return new MerkleProof(index, leafCount(), Sha256.toHex(levels.get(0)[index]), List.copyOf(steps));
    }

    /** Recomputes the root from a leaf hash and proof and compares it in constant time. */
    public static boolean verify(MerkleProof proof, byte[] expectedRoot) {
        byte[] h = Sha256.fromHex(proof.leafHashHex());
        for (MerkleProof.Step s : proof.steps()) {
            byte[] sib = Sha256.fromHex(s.siblingHex());
            h = s.siblingOnLeft() ? nodeHash(sib, h) : nodeHash(h, sib);
        }
        return java.security.MessageDigest.isEqual(h, expectedRoot);
    }

    @Override
    public String toString() {
        return "MerkleTree{leaves=" + leafCount() + ", root=" + rootHex() + ", levels=" + Arrays.toString(new int[]{levels.size()}) + "}";
    }
}
