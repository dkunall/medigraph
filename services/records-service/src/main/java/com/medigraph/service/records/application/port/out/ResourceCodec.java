package com.medigraph.service.records.application.port.out;

import com.medigraph.common.fhir.FhirResource;

/** Canonical FHIR JSON bytes. The hash on the ledger is over exactly these bytes, so encoding must be stable. */
public interface ResourceCodec {

    byte[] encode(FhirResource resource);

    FhirResource decode(byte[] json);

    /** Generic tree for responses (keeps unknown fields a client sent); opaque to the application layer. */
    Object tree(byte[] json);
}
