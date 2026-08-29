package dev.reasonweave.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.reasonweave.investigation.ScoringEngine.EvidenceFact;
import dev.reasonweave.investigation.ScoringEngine.ExpectedRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringEngineTest {
    private final ScoringEngine engine = new ScoringEngine();

    @Test
    void calculatesDocumentedContributionScoreAndCoverage() {
        var rules = List.of(
            new ExpectedRule("support", "1", "h1", "rain", "SUPPORTS", 0.8, true),
            new ExpectedRule("contradict", "1", "h1", "closed", "CONTRADICTS", 0.6, false),
            new ExpectedRule("missing", "1", "h1", "seal", "STRONGLY_SUPPORTS", 1.0, false)
        );
        var facts = List.of(
            new EvidenceFact("ev1", "obs1", "rain", true, 0.9, 0.8, 1.0),
            new EvidenceFact("ev2", "obs2", "closed", true, 0.95, 1.0, 1.0)
        );

        var result = engine.score(rules, facts);

        assertThat(result.contributions()).hasSize(2);
        assertThat(result.positive()).isCloseTo(0.432, within(0.000001));
        assertThat(result.negative()).isCloseTo(0.4275, within(0.000001));
        assertThat(result.missingPenalty()).isCloseTo(0.1, within(0.000001));
        assertThat(result.coverage()).isCloseTo(1.4 / 2.4, within(0.000001));
        assertThat(result.score()).isEqualTo(48);
    }

    @Test
    void knowledgeHitsCannotCreateContributions() {
        var rules = List.of(new ExpectedRule(
            "requires_observation", "1", "h1", "visible_damage", "SUPPORTS", 1.0, false
        ));

        var result = engine.score(rules, List.of());

        assertThat(result.contributions()).isEmpty();
        assertThat(result.coverage()).isZero();
        assertThat(result.score()).isEqualTo(45);
    }

    @Test
    void cannotAttributeACauseWithoutItsRequiredStrongEvidence() {
        var rules = List.of(
            new ExpectedRule("required", "1", "h1", "cause_detected", "STRONGLY_SUPPORTS", 1.0, true),
            new ExpectedRule("supplement", "1", "h1", "alarm", "STRONGLY_SUPPORTS", 1.0, false)
        );
        var facts = List.of(
            new EvidenceFact("ev1", "obs1", "alarm", true, 1.0, 1.0, 1.0)
        );

        var result = engine.score(rules, facts);

        assertThat(result.positive()).isEqualTo(1.0);
        assertThat(result.score()).isEqualTo(49);
        assertThat(result.band()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    void exposesEveryDocumentedRelationCoefficient() {
        assertThat(ScoringEngine.relationCoefficient("STRONGLY_SUPPORTS")).isEqualTo(1.0);
        assertThat(ScoringEngine.relationCoefficient("SUPPORTS")).isEqualTo(0.75);
        assertThat(ScoringEngine.relationCoefficient("PARTIALLY_SUPPORTS")).isEqualTo(0.40);
        assertThat(ScoringEngine.relationCoefficient("NEUTRAL")).isZero();
        assertThat(ScoringEngine.relationCoefficient("INSUFFICIENT")).isZero();
        assertThat(ScoringEngine.relationCoefficient("PARTIALLY_CONTRADICTS")).isEqualTo(-0.40);
        assertThat(ScoringEngine.relationCoefficient("CONTRADICTS")).isEqualTo(-0.75);
        assertThat(ScoringEngine.relationCoefficient("STRONGLY_CONTRADICTS")).isEqualTo(-1.0);
        assertThatThrownBy(() -> ScoringEngine.relationCoefficient("KNOWLEDGE_HIT"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
