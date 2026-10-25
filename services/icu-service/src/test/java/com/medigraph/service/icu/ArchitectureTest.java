package com.medigraph.service.icu;

import com.medigraph.arch.HexagonalRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    @Test
    void followsHexagonalLayering() {
        HexagonalRules.check("com.medigraph.service.icu");
    }
}
