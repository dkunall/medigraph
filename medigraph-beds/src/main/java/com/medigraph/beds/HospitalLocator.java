package com.medigraph.beds;

import com.medigraph.beds.geo.GeoPoint;

import java.util.List;

/** Port: nearest K hospitals with at least one free ICU bed. Adapters: in-memory haversine scan, PostGIS KNN. */
public interface HospitalLocator {
    List<NearbyHospital> nearestWithFreeIcu(GeoPoint from, int k, double maxKm);
}
