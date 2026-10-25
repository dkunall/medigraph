package com.medigraph.beds;

import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.beds.geo.Haversine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryHospitalLocatorTest {

    private final InMemoryBedRepository beds = new InMemoryBedRepository();
    private final InMemoryHospitalLocator locator = new InMemoryHospitalLocator(beds);

    private void add(String id, double lat, double lon, int free) {
        locator.add(new Hospital(id, id, new GeoPoint(lat, lon)));
        beds.upsert(new BedInventory(id, 10, 10 - free, 0, 0));
    }

    @Test
    void returnsNearestKWithFreeBedsSkippingFullOnes() {
        GeoPoint cp = new GeoPoint(28.6315, 77.2167); // Connaught Place
        add("AIIMS", 28.5672, 77.2100, 2);
        add("SAFDARJUNG", 28.5685, 77.2066, 0);   // full
        add("RML", 28.6262, 77.2005, 1);
        add("LNJP", 28.6390, 77.2370, 3);
        add("GURGAON", 28.4595, 77.0266, 5);
        List<NearbyHospital> r = locator.nearestWithFreeIcu(cp, 3, 50);
        assertThat(r).extracting(n -> n.hospital().id()).containsExactly("RML", "LNJP", "AIIMS");
        assertThat(r.get(0).freeIcuBeds()).isEqualTo(1);
        assertThat(locator.nearestWithFreeIcu(cp, 10, 5)).extracting(n -> n.hospital().id())
                .doesNotContain("GURGAON", "SAFDARJUNG");
        assertThat(locator.nearestWithFreeIcu(cp, 0, 50)).isEmpty();
    }

    @Test
    void matchesBruteForceOnRandomData() {
        Random rnd = new Random(11);
        List<Hospital> all = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String id = "H" + i;
            double lat = 8 + rnd.nextDouble() * 27, lon = 68 + rnd.nextDouble() * 29;
            add(id, lat, lon, rnd.nextInt(4));
            all.add(new Hospital(id, id, new GeoPoint(lat, lon)));
        }
        GeoPoint q = new GeoPoint(21.0, 79.0);
        List<String> expected = all.stream()
                .filter(h -> beds.find(h.id()).orElseThrow().free() > 0)
                .sorted(Comparator.comparingDouble(h -> Haversine.km(q, h.location())))
                .limit(7).map(Hospital::id).toList();
        assertThat(locator.nearestWithFreeIcu(q, 7, 10_000)).extracting(n -> n.hospital().id()).isEqualTo(expected);
    }
}
