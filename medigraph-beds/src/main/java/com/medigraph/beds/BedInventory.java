package com.medigraph.beds;

/**
 * ICU bed counters for one hospital with an optimistic-lock {@code version}. Every change produces a new value with
 * version + 1 and is written with compare-and-set on the old version, so two writers that read the same version
 * cannot both succeed.
 */
public record BedInventory(String hospitalId, int total, int occupied, int held, long version) {

    public BedInventory {
        if (total < 0 || occupied < 0 || held < 0 || occupied + held > total) {
            throw new IllegalStateException("invariant violated: occupied+held<=total for " + hospitalId
                    + " (" + occupied + "+" + held + "/" + total + ")");
        }
    }

    public int free() {
        return total - occupied - held;
    }

    public BedInventory withHeld(int delta) {
        return new BedInventory(hospitalId, total, occupied, held + delta, version + 1);
    }

    public BedInventory admitFromHold() {
        return new BedInventory(hospitalId, total, occupied + 1, held - 1, version + 1);
    }

    public BedInventory discharge() {
        return new BedInventory(hospitalId, total, occupied - 1, held, version + 1);
    }
}
