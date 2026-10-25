package com.medigraph.prescription;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrugCatalogTest {

    private final DrugCatalog catalog = DrugCatalog.loadSample();

    @Test
    void loadsSampleDataset() {
        assertThat(catalog.drugs()).hasSizeGreaterThan(40);
        assertThat(catalog.graph().edgeCount()).isGreaterThan(20);
        assertThat(catalog.graph().between("aspirin", "warfarin")).get()
                .extracting(Interaction::severity).isEqualTo(Severity.MAJOR); // undirected
        assertThat(catalog.trie().withPrefix("war", 5)).containsExactly("warfarin");
        assertThat(DrugCatalog.SAMPLE_DATA_NOTICE).contains("Not clinical advice");
    }

    @Test
    void rejectsInteractionsWithUnknownDrugs() {
        assertThatThrownBy(() -> DrugCatalog.parse(List.of("DRUG|a|x", "INTERACTION|a|b|MAJOR|?")))
                .hasMessageContaining("line 2");
    }
}
