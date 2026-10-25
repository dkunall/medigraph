package com.medigraph.beds.geo;

public record GeoPoint(double lat, double lon) {
    public GeoPoint {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("invalid coordinates " + lat + "," + lon);
        }
    }
}
