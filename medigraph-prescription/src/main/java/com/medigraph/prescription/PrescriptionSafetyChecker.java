package com.medigraph.prescription;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a proposed drug against the patient's active medications and recorded allergies:
 * drug-drug interactions (graph edges), direct allergy (allergen equals the drug or one of its classes),
 * cross-reactivity (allergen class -> related drug class), and duplicate therapy (same pharmacological class).
 * Unknown current medications are skipped (and reported), they never crash the check.
 */
public final class PrescriptionSafetyChecker {

    private static final Set<String> BROAD_CLASSES = Set.of("antibiotic");

    private final DrugCatalog catalog;
    private final DrugNameResolver resolver;

    public PrescriptionSafetyChecker(DrugCatalog catalog) {
        this.catalog = catalog;
        this.resolver = new DrugNameResolver(catalog);
    }

    public SafetyReport check(String proposed, Collection<String> currentMeds, Collection<String> allergies) {
        DrugNameResolver.Resolution r = resolver.resolve(proposed);
        if (!r.resolved()) {
            return new SafetyReport(r, List.of(), DrugCatalog.SAMPLE_DATA_NOTICE);
        }
        Drug drug = catalog.drug(r.drug()).orElseThrow();
        List<SafetyAlert> alerts = new ArrayList<>();

        List<String> current = new ArrayList<>();
        for (String m : currentMeds) {
            DrugNameResolver.Resolution cr = resolver.resolve(m);
            if (cr.resolved() && !cr.drug().equals(drug.name())) {
                current.add(cr.drug());
            }
        }
        for (Interaction i : catalog.graph().interactionsWith(drug.name(), current)) {
            alerts.add(new SafetyAlert(SafetyAlert.Type.INTERACTION, i.severity(), i.other(drug.name()),
                    drug.name() + " + " + i.other(drug.name()) + ": " + i.description()));
        }

        for (String raw : allergies) {
            String allergen = DrugCatalog.normalize(raw);
            if (allergen.equals(drug.name()) || drug.classes().contains(allergen)) {
                alerts.add(new SafetyAlert(SafetyAlert.Type.ALLERGY, Severity.CONTRAINDICATED, allergen,
                        "recorded allergy to " + allergen + "; " + drug.name() + " is/contains it"));
                continue;
            }
            Set<String> allergenClasses = new HashSet<>();
            allergenClasses.add(allergen);
            catalog.drug(allergen).ifPresent(a -> allergenClasses.addAll(a.classes()));
            for (CrossReactivity x : catalog.crossReactivity()) {
                if (allergenClasses.contains(x.allergenClass()) && drug.classes().contains(x.drugClass())) {
                    alerts.add(new SafetyAlert(SafetyAlert.Type.ALLERGY_CROSS_REACTIVITY, x.severity(), allergen,
                            x.description()));
                }
            }
        }

        for (String m : current) {
            Drug other = catalog.drug(m).orElseThrow();
            Optional<String> shared = drug.classes().stream()
                    .filter(c -> !BROAD_CLASSES.contains(c) && other.classes().contains(c)).sorted().findFirst();
            shared.ifPresent(c -> alerts.add(new SafetyAlert(SafetyAlert.Type.DUPLICATE_THERAPY, Severity.MODERATE,
                    m, "both " + drug.name() + " and " + m + " are " + c)));
        }
        alerts.sort(Comparator.comparing(SafetyAlert::severity).reversed());
        return new SafetyReport(r, List.copyOf(alerts), DrugCatalog.SAMPLE_DATA_NOTICE);
    }

    public DrugNameResolver resolver() {
        return resolver;
    }
}
