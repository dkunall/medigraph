package com.medigraph.beds;

import com.medigraph.beds.geo.GeoPoint;

public record Hospital(String id, String name, GeoPoint location) {
}
