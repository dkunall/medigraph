package com.medigraph.beds;

import java.util.Optional;

/** Port. JDBC adapter: {@code UPDATE ... SET ..., version = version + 1 WHERE hospital_id = ? AND version = ?}. */
public interface BedRepository {

    Optional<BedInventory> find(String hospitalId);

    /** Writes {@code updated} only if the stored row still has {@code expected.version()}. */
    boolean compareAndSet(BedInventory expected, BedInventory updated);

    void upsert(BedInventory inventory);
}
