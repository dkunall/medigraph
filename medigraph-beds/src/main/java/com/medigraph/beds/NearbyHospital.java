package com.medigraph.beds;

public record NearbyHospital(Hospital hospital, double distanceKm, int freeIcuBeds) {
}
