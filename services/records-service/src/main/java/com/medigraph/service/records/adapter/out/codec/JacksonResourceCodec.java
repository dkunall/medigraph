package com.medigraph.service.records.adapter.out.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.fhir.FhirResource;
import com.medigraph.service.records.application.port.out.ResourceCodec;

import java.io.IOException;

public final class JacksonResourceCodec implements ResourceCodec {

    private final ObjectMapper json;

    public JacksonResourceCodec(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public byte[] encode(FhirResource resource) {
        try {
            return json.writerFor(FhirResource.class).writeValueAsBytes(resource);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserialisable FHIR resource", e);
        }
    }

    @Override
    public FhirResource decode(byte[] bytes) {
        try {
            return json.readValue(bytes, FhirResource.class);
        } catch (IOException e) {
            throw new IllegalStateException("stored resource is not valid FHIR JSON", e);
        }
    }

    @Override
    public Object tree(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("stored resource is not valid JSON", e);
        }
    }
}
