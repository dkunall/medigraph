package com.medigraph.beds.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HaversineTest {

    @Test
    void knownCityDistances() {
        GeoPoint delhi = new GeoPoint(28.6139, 77.2090);
        GeoPoint mumbai = new GeoPoint(19.0760, 72.8777);
        GeoPoint bengaluru = new GeoPoint(12.9716, 77.5946);
        assertThat(Haversine.km(delhi, mumbai)).isCloseTo(1153, within(10.0));
        assertThat(Haversine.km(delhi, bengaluru)).isCloseTo(1740, within(15.0));
        assertThat(Haversine.km(delhi, delhi)).isZero();
        assertThat(Haversine.km(delhi, mumbai)).isEqualTo(Haversine.km(mumbai, delhi));
    }

    @Test
    void antipodesAreHalfTheCircumference() {
        assertThat(Haversine.km(new GeoPoint(0, 0), new GeoPoint(0, 180)))
                .isCloseTo(Math.PI * Haversine.EARTH_RADIUS_KM, within(0.001));
    }
}
