package com.medigraph.beds.geo;

/**
 * Great-circle distance on a sphere of mean Earth radius (error vs WGS-84 ellipsoid is under ~0.5%).
 *
 * <p>Complexity: O(1) time and space per pair.
 */
public final class Haversine {

    public static final double EARTH_RADIUS_KM = 6371.0088;

    private Haversine() {
    }

    public static double km(GeoPoint a, GeoPoint b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
