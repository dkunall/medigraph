package com.medigraph.common;

import com.medigraph.common.id.FacilityId;
import com.medigraph.common.id.HealthId;
import com.medigraph.common.id.InvalidHealthIdException;
import com.medigraph.common.security.Actor;
import com.medigraph.common.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorTest {

    @Test
    void patientActorIdMustBeAValidHealthIdAndIsNormalized() {
        HealthId h = HealthId.generate();
        Actor a = new Actor(h.formatted(), Set.of(Role.PATIENT), null);
        assertThat(a.id()).isEqualTo(h.digits());
        assertThat(a.isPatientSelf(Set.of(h.digits()))).isTrue();
        assertThatThrownBy(() -> new Actor("12345", Set.of(Role.PATIENT), null)).isInstanceOf(InvalidHealthIdException.class);
    }

    @Test
    void staffNeedAFacilityAndAtLeastOneRole() {
        assertThatThrownBy(() -> new Actor("dr-a", Set.of(Role.DOCTOR), null)).hasMessageContaining("facility");
        assertThatThrownBy(() -> new Actor("dr-a", Set.of(), "HOSP-A")).hasMessageContaining("role");
        Actor d = new Actor("dr-a", Set.of(Role.DOCTOR), "HOSP-A");
        assertThat(d.isClinician()).isTrue();
        assertThat(d.isPatientSelf(Set.of("dr-a"))).isFalse();
        assertThat(new Actor("records-service", Set.of(Role.SERVICE), null).has(Role.SERVICE)).isTrue();
    }

    @Test
    void facilityIdShapeIsEnforced() {
        assertThat(new FacilityId("HOSP-A").value()).isEqualTo("HOSP-A");
        assertThatThrownBy(() -> new FacilityId("hosp a")).isInstanceOf(IllegalArgumentException.class);
    }
}
