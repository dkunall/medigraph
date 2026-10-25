package com.medigraph.prescription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DrugNameResolverTest {

    private final DrugNameResolver resolver = new DrugNameResolver(DrugCatalog.loadSample());

    @ParameterizedTest
    @CsvSource({"metfromin,metformin", "warfrin,warfarin", "Amoxicilin,amoxicillin", "atorvastatn,atorvastatin",
            "ibuprofin,ibuprofen", "sertralin,sertraline"})
    void correctsTypos(String typed, String expected) {
        DrugNameResolver.Resolution r = resolver.resolve(typed);
        assertThat(r.kind()).isEqualTo(DrugNameResolver.Kind.CORRECTED);
        assertThat(r.drug()).isEqualTo(expected);
    }

    @Test
    void exactMatchIsCaseAndWhitespaceInsensitive() {
        assertThat(resolver.resolve("  Calcium   Carbonate ").kind()).isEqualTo(DrugNameResolver.Kind.EXACT);
    }

    @Test
    void gibberishIsUnknownNotGuessed() {
        DrugNameResolver.Resolution r = resolver.resolve("zzzzqqqq");
        assertThat(r.kind()).isEqualTo(DrugNameResolver.Kind.UNKNOWN);
        assertThat(r.drug()).isNull();
    }

    @Test
    void equidistantCandidatesAreAmbiguous() {
        // "atenolol" vs a made-up "atenolal"/"atenolil" style tie: build a tiny catalog to force it
        DrugCatalog c = DrugCatalog.parse(java.util.List.of("DRUG|abcdefg|x", "DRUG|abcdefh|x"));
        DrugNameResolver.Resolution r = new DrugNameResolver(c).resolve("abcdefz");
        assertThat(r.kind()).isEqualTo(DrugNameResolver.Kind.AMBIGUOUS);
        assertThat(r.suggestions()).containsExactly("abcdefg", "abcdefh");
    }
}
