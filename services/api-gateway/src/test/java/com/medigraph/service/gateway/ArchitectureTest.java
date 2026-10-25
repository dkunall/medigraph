package com.medigraph.service.gateway;

import com.medigraph.arch.HexagonalRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    @Test
    void followsHexagonalLayering() {
        HexagonalRules.check("com.medigraph.service.gateway");
    }
}
