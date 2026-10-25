package com.medigraph.service.beds.adapter.out.persistence;

import com.medigraph.beds.Hospital;
import com.medigraph.beds.HospitalLocator;
import com.medigraph.beds.NearbyHospital;
import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.beds.geo.Haversine;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Default locator on plain PostgreSQL (no PostGIS needed), correct across any number of service instances because
 * it reads the shared tables rather than an in-process index.
 *
 * <ol>
 *   <li>SQL prefilter: a lat/lon bounding box around the radius (B-tree index on {@code (lat, lon)}) joined to
 *       inventory with {@code free > 0}. Over-approximates the circle; never misses a candidate.</li>
 *   <li>Exact great-circle distance (haversine) per candidate and a bounded max-heap of size k: the heap root is
 *       the farthest of the current best k, so a closer candidate replaces it in O(log k).</li>
 * </ol>
 * Complexity: O(m log k) time and O(k) extra space for m candidates in the box (m &lt;&lt; n hospitals). The
 * PostGIS adapter replaces step 1 with a GiST KNN scan.
 */
public final class JdbcHaversineLocator implements HospitalLocator {

    private static final double KM_PER_DEG_LAT = 111.32;

    private final JdbcTemplate jdbc;

    public JdbcHaversineLocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NearbyHospital> nearestWithFreeIcu(GeoPoint from, int k, double maxKm) {
        if (k <= 0 || maxKm <= 0) {
            return List.of();
        }
        double dLat = maxKm / KM_PER_DEG_LAT;
        double cos = Math.max(Math.cos(Math.toRadians(from.lat())), 1e-6);
        double dLon = Math.min(180, maxKm / (KM_PER_DEG_LAT * cos));
        // near the antimeridian the box would wrap; fall back to a latitude-only box there
        boolean wraps = from.lon() - dLon < -180 || from.lon() + dLon > 180;
        String sql = "SELECT h.id, h.name, h.lat, h.lon, b.total - b.occupied - b.held AS free "
                + "FROM hospital h JOIN bed_inventory b ON b.hospital_id = h.id "
                + "WHERE b.total - b.occupied - b.held > 0 AND h.lat BETWEEN ? AND ?"
                + (wraps ? "" : " AND h.lon BETWEEN ? AND ?");
        Object[] args = wraps ? new Object[]{from.lat() - dLat, from.lat() + dLat}
                : new Object[]{from.lat() - dLat, from.lat() + dLat, from.lon() - dLon, from.lon() + dLon};

        PriorityQueue<NearbyHospital> heap = new PriorityQueue<>(k + 1,
                Comparator.comparingDouble(NearbyHospital::distanceKm).reversed());
        jdbc.query(sql, rs -> {
            Hospital h = new Hospital(rs.getString("id"), rs.getString("name"),
                    new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon")));
            double d = Haversine.km(from, h.location());
            if (d > maxKm || (heap.size() == k && d >= heap.peek().distanceKm())) {
                return;
            }
            heap.offer(new NearbyHospital(h, d, rs.getInt("free")));
            if (heap.size() > k) {
                heap.poll();
            }
        }, args);
        List<NearbyHospital> out = new ArrayList<>(heap);
        out.sort(Comparator.comparingDouble(NearbyHospital::distanceKm));
        return out;
    }
}
