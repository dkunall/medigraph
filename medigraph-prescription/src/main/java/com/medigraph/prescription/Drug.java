package com.medigraph.prescription;

import java.util.Set;

public record Drug(String name, Set<String> classes) {
    public Drug {
        name = DrugCatalog.normalize(name);
        classes = Set.copyOf(classes);
    }
}
