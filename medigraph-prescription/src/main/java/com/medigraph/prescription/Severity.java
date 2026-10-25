package com.medigraph.prescription;

public enum Severity {
    MINOR, MODERATE, MAJOR, CONTRAINDICATED;

    /** MAJOR and above block the prescription unless a doctor overrides with a reason. */
    public boolean blocking() {
        return this.compareTo(MAJOR) >= 0;
    }
}
