package com.medigraph.service.gateway.domain;

import java.util.regex.Pattern;

/** Logical name of a backend service a route points at (resolved to a URL by the outbound adapter). */
public record RouteTarget(String service) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,40}");

    public RouteTarget {
        if (service == null || !NAME.matcher(service).matches()) {
            throw new IllegalArgumentException("invalid service name: " + service);
        }
    }
}
