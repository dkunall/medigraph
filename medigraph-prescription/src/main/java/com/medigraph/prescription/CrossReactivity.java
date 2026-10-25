package com.medigraph.prescription;

public record CrossReactivity(String allergenClass, String drugClass, Severity severity, String description) {
}
