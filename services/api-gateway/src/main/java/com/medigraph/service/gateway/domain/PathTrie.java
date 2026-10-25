package com.medigraph.service.gateway.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Segment trie for route patterns such as {@code /api/v1/icu} or {@code /api/v1/patients/{@literal *}/consents}.
 * A pattern matches a request path when it is a segment-wise prefix of it; {@code *} matches exactly one segment.
 * When several patterns match, the most specific wins: the deepest match, and at equal depth a literal segment beats
 * {@code *}. So {@code /api/v1/patients/X/consents} goes to consent while {@code /api/v1/patients/X} falls back to
 * the shorter identity route.
 *
 * <p>Why a trie and not a list of regexes: lookup cost depends on the path length, not on the number of routes.
 * <ul>
 *   <li>insert: O(s) for a pattern of s segments (hash-map child per segment).</li>
 *   <li>match without wildcards: O(s) for a path of s segments.</li>
 *   <li>match with wildcards: depth-first, literal child first; each node has at most one wildcard child, so the
 *       worst case is O(2^s) visits, in practice O(s) because route tables use a wildcard in one or two
 *       positions.</li>
 *   <li>space: O(total segments of all patterns).</li>
 * </ul>
 * Not thread-safe for writes; build once, then read concurrently (the gateway never mutates it after start-up).
 */
public final class PathTrie<V> {

    public static final String WILDCARD = "*";

    private static final class Node<V> {
        final Map<String, Node<V>> literal = new HashMap<>();
        Node<V> wildcard;
        V value;
    }

    private final Node<V> root = new Node<>();
    private int size;

    public void insert(String pattern, V value) {
        Objects.requireNonNull(value, "value");
        Node<V> n = root;
        for (String seg : segments(pattern)) {
            if (WILDCARD.equals(seg)) {
                if (n.wildcard == null) {
                    n.wildcard = new Node<>();
                }
                n = n.wildcard;
            } else {
                n = n.literal.computeIfAbsent(seg, k -> new Node<>());
            }
        }
        if (n.value != null) {
            throw new IllegalArgumentException("duplicate route pattern: " + pattern);
        }
        n.value = value;
        size++;
    }

    /** Most specific pattern that is a segment-prefix of {@code path}. */
    public Optional<V> match(String path) {
        String[] segs = segments(path);
        Best<V> best = new Best<>();
        search(root, segs, 0, 0, best);
        return Optional.ofNullable(best.value);
    }

    public int size() {
        return size;
    }

    private static final class Best<V> {
        V value;
        int depth = -1;
        int literals = -1;
    }

    private static <V> void search(Node<V> n, String[] segs, int depth, int literals, Best<V> best) {
        if (n.value != null && (depth > best.depth || (depth == best.depth && literals > best.literals))) {
            best.value = n.value;
            best.depth = depth;
            best.literals = literals;
        }
        if (depth == segs.length) {
            return;
        }
        Node<V> lit = n.literal.get(segs[depth]);
        if (lit != null) {
            search(lit, segs, depth + 1, literals + 1, best);
        }
        if (n.wildcard != null) {
            search(n.wildcard, segs, depth + 1, literals, best);
        }
    }

    static String[] segments(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/'");
        }
        String trimmed = path.replaceAll("^/+|/+$", "");
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/+");
    }
}
