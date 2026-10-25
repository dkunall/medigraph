package com.medigraph.beds;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryBedRepository implements BedRepository {

    private final ConcurrentMap<String, BedInventory> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<BedInventory> find(String hospitalId) {
        return Optional.ofNullable(rows.get(hospitalId));
    }

    @Override
    public boolean compareAndSet(BedInventory expected, BedInventory updated) {
        boolean[] ok = {false};
        rows.computeIfPresent(expected.hospitalId(), (k, cur) -> {
            if (cur.version() != expected.version()) {
                return cur;
            }
            ok[0] = true;
            return updated;
        });
        return ok[0];
    }

    @Override
    public void upsert(BedInventory inventory) {
        rows.put(inventory.hospitalId(), inventory);
    }
}
