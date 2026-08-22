package com.leadflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leadflow.qualification.Lead;
import com.leadflow.tenant.AssignmentStrategyType;
import com.leadflow.tenant.SalesRep;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AssignmentStrategyRegistryTest {

    /** Strategie factice : ce test porte sur la resolution, pas sur le choix. */
    private record StrategieFactice(AssignmentStrategyType type) implements AssignmentStrategy {

        @Override
        public Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles) {
            return Optional.empty();
        }
    }

    private static List<AssignmentStrategy> completes() {
        return List.of(
                new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN),
                new StrategieFactice(AssignmentStrategyType.GEOGRAPHIC),
                new StrategieFactice(AssignmentStrategyType.SECTOR));
    }

    @Test
    void rendLaStrategieDuTypeDemande() {
        AssignmentStrategyRegistry registre = new AssignmentStrategyRegistry(completes());

        assertThat(registre.pour(AssignmentStrategyType.SECTOR).type())
                .isEqualTo(AssignmentStrategyType.SECTOR);
    }

    /** Une erreur de cablage doit se voir au demarrage, pas au premier lead. */
    @Test
    void refuseDeDemarrerSiUnTypeNAAucuneStrategie() {
        List<AssignmentStrategy> incompletes =
                List.of(new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN));

        assertThatThrownBy(() -> new AssignmentStrategyRegistry(incompletes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEOGRAPHIC");
    }

    @Test
    void refuseDeDemarrerSiDeuxStrategiesRevendiquentLeMemeType() {
        List<AssignmentStrategy> doublon = List.of(
                new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN),
                new StrategieFactice(AssignmentStrategyType.ROUND_ROBIN),
                new StrategieFactice(AssignmentStrategyType.GEOGRAPHIC),
                new StrategieFactice(AssignmentStrategyType.SECTOR));

        assertThatThrownBy(() -> new AssignmentStrategyRegistry(doublon))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUND_ROBIN");
    }
}
