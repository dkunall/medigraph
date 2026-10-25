package com.medigraph.beds;

import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.beds.geo.Haversine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Linear scan with a bounded max-heap of size K: O(n log k) time, O(k) extra space. Fine for thousands of
 * hospitals; the PostGIS adapter uses a GiST index ({@code ORDER BY geom <-> point}) for larger sets.
 */
public final class InMemoryHospitalLocator implements HospitalLocator {

    private final List<Hospital> hospitals = new CopyOnWriteArrayList<>();
    private final BedRepository beds;

    public InMemoryHospitalLocator(BedRepository beds) {
        this.beds = beds;
    }

    /** Adds or replaces (by id) a hospital. */
    public synchronized void add(Hospital h) {
        hospitals.removeIf(x -> x.id().equals(h.id()));
        hospitals.add(h);
    }

    @Override
    public List<NearbyHospital> nearestWithFreeIcu(GeoPoint from, int k, double maxKm) {
        if (k <= 0) {
            return List.of();
        }
        PriorityQueue<NearbyHospital> heap = new PriorityQueue<>(
                Comparator.comparingDouble(NearbyHospital::distanceKm).reversed());
        for (Hospital h : hospitals) {
            double d = Haversine.km(from, h.location());
            if (d > maxKm || (heap.size() == k && d >= heap.peek().distanceKm())) {
                continue; // prune before touching the bed repository
            }
            int free = beds.find(h.id()).map(BedInventory::free).orElse(0);
            if (free <= 0) {
                continue;
            }
            heap.offer(new NearbyHospital(h, d, free));
            if (heap.size() > k) {
                heap.poll();
            }
        }
        List<NearbyHospital> out = new ArrayList<>(heap);
        out.sort(Comparator.comparingDouble(NearbyHospital::distanceKm));
        return out;
    }
}
