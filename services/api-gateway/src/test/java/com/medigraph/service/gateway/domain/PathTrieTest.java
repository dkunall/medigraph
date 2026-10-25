package com.medigraph.service.gateway.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathTrieTest {

    private PathTrie<String> trie() {
        PathTrie<String> t = new PathTrie<>();
        t.insert("/api/v1/patients", "identity");
        t.insert("/api/v1/patients/*/consents", "consent");
        t.insert("/api/v1/patients/*/records", "records");
        t.insert("/api/v1/patients/special/records", "special");
        t.insert("/api/v1/icu", "icu");
        return t;
    }

    @Test
    void mostSpecificPatternWins() {
        PathTrie<String> t = trie();
        assertThat(t.match("/api/v1/patients")).contains("identity");
        assertThat(t.match("/api/v1/patients/123")).contains("identity");
        assertThat(t.match("/api/v1/patients/123/erasure")).contains("identity");
        assertThat(t.match("/api/v1/patients/123/consents")).contains("consent");
        assertThat(t.match("/api/v1/patients/123/consents/9")).contains("consent");
        assertThat(t.match("/api/v1/patients/123/records")).contains("records");
        assertThat(t.match("/api/v1/patients/special/records")).contains("special"); // literal beats wildcard
        assertThat(t.match("/api/v1/icu/patients/1/vitals")).contains("icu");
        assertThat(t.match("//api//v1/icu/")).contains("icu");
    }

    @Test
    void noMatchAndSegmentBoundaries() {
        PathTrie<String> t = trie();
        assertThat(t.match("/api/v1/icux")).isEmpty(); // prefix match is per segment, not per character
        assertThat(t.match("/api/v2/icu")).isEmpty();
        assertThat(t.match("/")).isEmpty();
        assertThat(t.size()).isEqualTo(5);
    }

    @Test
    void rejectsDuplicatesAndRelativePaths() {
        PathTrie<String> t = trie();
        assertThatThrownBy(() -> t.insert("/api/v1/icu/", "dup")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.match("api/v1")).isInstanceOf(IllegalArgumentException.class);
    }
}
