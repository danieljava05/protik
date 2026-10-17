package com.protik;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Module boundaries are a build-failing rule, not a convention (ADR 0002). */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(ProtikApplication.class);

    @Test
    void modulesAreExactlyTheDocumentedOnes() {
        Set<String> names = MODULES.stream().map(m -> m.getIdentifier().toString()).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "identity", "video", "feed", "social", "engagement", "moderation", "shared");
    }

    @Test
    void everyModuleHasMigrationsAndNothingElseIsMigrated() {
        // Guards drift between the code's modules and the migration runner (ADR 0007).
        Set<String> names = MODULES.stream().map(m -> m.getIdentifier().toString()).collect(Collectors.toSet());
        assertThat(com.protik.shared.migration.ModuleMigrations.MODULES).containsExactlyInAnyOrderElementsOf(names);
        assertThat(com.protik.shared.migration.ModuleMigrations.MODULES.getFirst()).isEqualTo("shared");
    }

    @Test
    void noModuleViolatesBoundariesOrHasCycles() {
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
