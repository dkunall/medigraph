package com.medigraph.ledger.merkle;

import com.medigraph.common.crypto.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerkleTreeTest {

    private static List<byte[]> leaves(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(MerkleTree.leafHash(("record-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    @Test
    void singleLeafRootIsTheLeafHash() {
        List<byte[]> l = leaves(1);
        MerkleTree t = MerkleTree.fromLeafHashes(l);
        assertThat(t.root()).isEqualTo(l.get(0));
        assertThat(t.proof(0).steps()).isEmpty();
        assertThat(MerkleTree.verify(t.proof(0), t.root())).isTrue();
    }

    @Test
    void twoLeafRootIsPrefixedHashOfChildren() {
        List<byte[]> l = leaves(2);
        byte[] expected = Sha256.digest(new byte[]{0x01}, l.get(0), l.get(1));
        assertThat(MerkleTree.fromLeafHashes(l).root()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 7, 8, 9, 16, 17, 31, 100, 1000, 1025})
    void everyLeafHasAValidProofOfLogarithmicLength(int n) {
        MerkleTree t = MerkleTree.fromLeafHashes(leaves(n));
        int maxSteps = (int) Math.ceil(Math.log(n) / Math.log(2));
        for (int i = 0; i < n; i++) {
            MerkleProof p = t.proof(i);
            assertThat(p.steps().size()).isLessThanOrEqualTo(maxSteps);
            assertThat(MerkleTree.verify(p, t.root())).as("leaf %d of %d", i, n).isTrue();
        }
        assertThat(t.height()).isEqualTo(maxSteps);
    }

    @Test
    void proofForOtherLeafOrTamperedSiblingFails() {
        MerkleTree t = MerkleTree.fromLeafHashes(leaves(8));
        MerkleProof p = t.proof(3);
        MerkleProof wrongLeaf = new MerkleProof(3, 8, t.proof(4).leafHashHex(), p.steps());
        assertThat(MerkleTree.verify(wrongLeaf, t.root())).isFalse();

        List<MerkleProof.Step> steps = new ArrayList<>(p.steps());
        MerkleProof.Step s0 = steps.get(0);
        steps.set(0, new MerkleProof.Step(Sha256.hex("evil"), s0.siblingOnLeft()));
        assertThat(MerkleTree.verify(new MerkleProof(3, 8, p.leafHashHex(), steps), t.root())).isFalse();

        // flipping the order bit must also fail (concatenation is not commutative)
        List<MerkleProof.Step> flipped = new ArrayList<>(p.steps());
        flipped.set(0, new MerkleProof.Step(s0.siblingHex(), !s0.siblingOnLeft()));
        assertThat(MerkleTree.verify(new MerkleProof(3, 8, p.leafHashHex(), flipped), t.root())).isFalse();
    }

    @Test
    void changingAnyLeafChangesTheRoot() {
        List<byte[]> l = leaves(9);
        byte[] root = MerkleTree.fromLeafHashes(l).root();
        for (int i = 0; i < l.size(); i++) {
            List<byte[]> copy = new ArrayList<>(l);
            copy.set(i, MerkleTree.leafHash("tampered".getBytes()));
            assertThat(MerkleTree.fromLeafHashes(copy).root()).isNotEqualTo(root);
        }
    }

    @Test
    void oddLeafIsPromotedNotDuplicated() {
        // With duplication, [a,b,c] and [a,b,c,c] would share a root (CVE-2012-2459). Promotion avoids it.
        List<byte[]> three = leaves(3);
        List<byte[]> four = new ArrayList<>(three);
        four.add(three.get(2));
        assertThat(MerkleTree.fromLeafHashes(three).root()).isNotEqualTo(MerkleTree.fromLeafHashes(four).root());
    }

    @Test
    void leafAndNodeHashesAreDomainSeparated() {
        byte[] a = MerkleTree.leafHash("a".getBytes());
        byte[] b = MerkleTree.leafHash("b".getBytes());
        byte[] concatenated = new byte[64];
        System.arraycopy(a, 0, concatenated, 0, 32);
        System.arraycopy(b, 0, concatenated, 32, 32);
        assertThat(MerkleTree.leafHash(concatenated)).isNotEqualTo(MerkleTree.nodeHash(a, b));
    }

    @Test
    void rejectsEmptyAndBadIndex() {
        assertThatThrownBy(() -> MerkleTree.fromLeafHashes(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.fromLeafHashes(leaves(2)).proof(2)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
