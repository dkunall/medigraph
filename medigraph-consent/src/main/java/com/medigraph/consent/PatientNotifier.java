package com.medigraph.consent;

/** Notifies the patient (SMS/app push in production; a persisted notification row in this build). */
@FunctionalInterface
public interface PatientNotifier {
    void notify(String patientId, String kind, String message);
}
