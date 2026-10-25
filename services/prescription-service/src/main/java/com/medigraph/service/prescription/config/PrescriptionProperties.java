package com.medigraph.service.prescription.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("medigraph.prescription")
public record PrescriptionProperties(@DefaultValue("http://localhost:8082") String recordsUrl) {
}
