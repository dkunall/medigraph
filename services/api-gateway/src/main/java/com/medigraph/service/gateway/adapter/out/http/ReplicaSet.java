package com.medigraph.service.gateway.adapter.out.http;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Base URLs of one service's replicas, plus the index of the one that answered last. Thread-safe. */
final class ReplicaSet {

    private final List<URI> urls;
    private final AtomicInteger preferred = new AtomicInteger();

    ReplicaSet(List<URI> urls) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("a service needs at least one URL");
        }
        this.urls = List.copyOf(urls);
    }

    int size() {
        return urls.size();
    }

    URI get(int index) {
        return urls.get(index);
    }

    int preferred() {
        return preferred.get();
    }

    void prefer(int index) {
        preferred.set(index);
    }
}
