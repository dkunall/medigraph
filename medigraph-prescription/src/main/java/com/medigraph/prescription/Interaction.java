package com.medigraph.prescription;

/** Undirected weighted edge of the interaction graph. */
public record Interaction(String drugA, String drugB, Severity severity, String description) {

    public String other(String drug) {
        return drug.equals(drugA) ? drugB : drugA;
    }
}
