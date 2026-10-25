package com.medigraph.common.fhir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medigraph.common.fhir.FhirTypes.CodeableConcept;
import com.medigraph.common.fhir.FhirTypes.Quantity;
import com.medigraph.common.fhir.FhirTypes.Reference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FhirJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesWithResourceTypeDiscriminatorAndRoundTrips() throws Exception {
        FhirResource obs = new Observation("o1", Reference.patient("p1"), "final",
                CodeableConcept.of("http://loinc.org", "8867-4", "Heart rate"), new Quantity(88, "beats/min"),
                "2026-10-25T10:00:00Z");
        String json = mapper.writeValueAsString(obs);
        assertThat(json).contains("\"resourceType\":\"Observation\"").doesNotContain("category");
        FhirResource back = mapper.readValue(json, FhirResource.class);
        assertThat(back).isEqualTo(obs);
        assertThat(back.category()).isEqualTo(DataCategory.OBSERVATIONS);
    }

    @Test
    void parsesFhirStyleConditionJson() throws Exception {
        String json = """
                {"resourceType":"Condition","id":"c1","subject":{"reference":"Patient/p1"},
                 "code":{"coding":[{"system":"http://snomed.info/sct","code":"44054006","display":"Type 2 diabetes"}],"text":"T2DM"},
                 "clinicalStatus":"active","onsetDateTime":"2020-01-01"}
                """;
        FhirResource r = mapper.readValue(json, FhirResource.class);
        assertThat(r).isInstanceOf(Condition.class);
        assertThat(((Condition) r).code().primaryCode()).isEqualTo("44054006");
        assertThat(r.category()).isEqualTo(DataCategory.CONDITIONS);
    }
}
