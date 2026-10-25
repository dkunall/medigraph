package com.medigraph.service.prescription.application;

import com.medigraph.consent.Purpose;

public record PrescribeCommand(String healthId, String drug, String dosage, String overrideReason, Purpose purpose) {
    public PrescribeCommand {
        purpose = purpose == null ? Purpose.TREATMENT : purpose;
    }
}
