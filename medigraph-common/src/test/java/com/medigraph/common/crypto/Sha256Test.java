package com.medigraph.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {
    @Test
    void knownVector() {
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hexRoundTrip() {
        byte[] d = Sha256.digest("x".getBytes());
        assertThat(Sha256.fromHex(Sha256.toHex(d))).isEqualTo(d);
    }
}
