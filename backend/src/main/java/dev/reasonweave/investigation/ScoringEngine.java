package dev.reasonweave.investigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ScoringEngine {
    public static final double REQUIRED_MISSING_LAMBDA = 0.35;
    public static final double OPTIONAL_MISSING_LAMBDA = 0.10;
    private static final Map<String, Double> RELATION_COEFFICIENTS = Map.of(
        "STRONGLY_SUPPORTS", 1.0,
        "SUPPORTS", 0.75,
        "PARTIALLY_SUPPORTS", 0.40,
        "NEUTRAL", 0.0,
        "INSUFFICIENT", 0.0,
        "PARTIALLY_CONTRADICTS", -0.40,
        "CONTRADICTS", -0.75,
        "STRONGLY_CONTRADICTS", -1.0
    );

    public ScoreResult score(List<ExpectedRule> rules, List<EvidenceFact> facts) {
        List<Contribution> contributions = new ArrayList<>();
        double capacity = rules.stream().mapToDouble(ExpectedRule::weight).sum();
        double coveredWeight = 0;
        double missingRequiredWeight = 0;
        double missingOptionalWeight = 0;

        for (ExpectedRule rule : rules) {
            EvidenceFact fact = facts.stream()
                .filter(value -> value.predicate().equals(rule.predicate()) && value.present())
                .max(Comparator.comparingDouble(value ->
                    value.sourceReliability() * value.extractionConfidence() * value.relevance()))
                .orElse(null);
            if (fact == null) {
                if (rule.required()) {
                    missingRequiredWeight += rule.weight();
                } else {
                    missingOptionalWeight += rule.weight();
                }
                continue;
            }
            coveredWeight += rule.weight();
            double coefficient = relationCoefficient(rule.relation());
            double value = rule.weight() * coefficient * fact.sourceReliability()
                * fact.extractionConfidence() * fact.relevance();
            contributions.add(new Contribution(
                rule.id(), rule.version(), rule.predicate(), rule.relation(), rule.weight(),
                fact.evidenceId(), fact.observationId(), fact.sourceReliability(),
                fact.extractionConfidence(), fact.relevance(), value,
                "规则 " + rule.id() + " 精确匹配 Observation " + fact.predicate()
            ));
        }

        double positive = contributions.stream().mapToDouble(value -> Math.max(value.value(), 0)).sum();
        double negative = contributions.stream().mapToDouble(value -> Math.abs(Math.min(value.value(), 0))).sum();
        double missingPenalty = missingRequiredWeight * REQUIRED_MISSING_LAMBDA
            + missingOptionalWeight * OPTIONAL_MISSING_LAMBDA;
        double normalized = capacity == 0 ? 0 : (positive - negative - missingPenalty) / capacity;
        int calculatedScore = (int) Math.round(clamp(50 + 50 * normalized, 0, 100));
        int score = missingRequiredWeight > 0 ? Math.min(calculatedScore, 49) : calculatedScore;
        double coverage = capacity == 0 ? 0 : coveredWeight / capacity;
        return new ScoreResult(
            score, band(score), coverage, positive, negative, missingPenalty,
            capacity, List.copyOf(contributions)
        );
    }

    public static double relationCoefficient(String relation) {
        Double value = RELATION_COEFFICIENTS.get(relation);
        if (value == null) {
            throw new IllegalArgumentException("Unsupported evidence relation: " + relation);
        }
        return value;
    }

    private static String band(int score) {
        if (score >= 70) {
            return "SUPPORTED";
        }
        if (score >= 55) {
            return "LEANING_SUPPORTED";
        }
        if (score >= 40) {
            return "INCONCLUSIVE";
        }
        return "CONTRADICTED";
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record ExpectedRule(
        String id,
        String version,
        String hypothesisCode,
        String predicate,
        String relation,
        double weight,
        boolean required
    ) {}

    public record EvidenceFact(
        String evidenceId,
        String observationId,
        String predicate,
        boolean present,
        double sourceReliability,
        double extractionConfidence,
        double relevance
    ) {}

    public record Contribution(
        String ruleId,
        String ruleVersion,
        String predicate,
        String relation,
        double ruleWeight,
        String evidenceId,
        String observationId,
        double sourceReliability,
        double extractionConfidence,
        double relevance,
        double value,
        String reason
    ) {}

    public record ScoreResult(
        int score,
        String band,
        double coverage,
        double positive,
        double negative,
        double missingPenalty,
        double capacity,
        List<Contribution> contributions
    ) {}
}
