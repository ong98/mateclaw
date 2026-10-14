package vip.mate.decision.provider;

import org.springframework.stereotype.Component;
import vip.mate.decision.api.*;
import vip.mate.goal.service.GoalDecisionFacts;

import java.util.Map;
import java.util.Set;

/**
 * Deterministic validation of the trusted goal snapshot, not semantic goal reasoning.
 * A confidence of one is a policy match score; it is not a calibrated model probability.
 */
@Component
public class RuleDecisionProvider implements DecisionProvider {
    private static final DecisionFacts SCHEMA = GoalDecisionFacts.snapshot(null, null);
    private static final Set<String> EVALUATION_FLAGS = Set.of("EVALUATION_VALID", "EVALUATION_CURRENT",
            "EVALUATION_STALE", "EVALUATION_CONTINUE", "EVALUATION_COMPLETED", "EVALUATION_FALLBACK");
    public String id() { return "rule"; }
    public DecisionCapability capability() { return DecisionCapability.standard(); }
    public DecisionResult decide(DecisionRequest request) {
        if (request.type() != DecisionType.GOAL_CONTINUATION
                || !(request.question() instanceof DecisionQuestion.Choice)
                || !GoalDecisionFacts.VERSION.equals(request.question().version())
                || !new DecisionValue.Choice("CONTINUE").equals(request.baseline())
                || request.guardValue() != null || request.policyOverride() != null
                || !continuable(request.facts())) return baseline(request);
        String choice = yes(request.facts().flags(), "EVALUATION_STALE") ? "RETRY" : "CONTINUE";
        DecisionValue value = new DecisionValue.Choice(choice);
        return request.question().accepts(value) ? DecisionResult.proposed(value, 1, "rule-goal-v2") : baseline(request);
    }

    private static DecisionResult baseline(DecisionRequest request) {
        return new DecisionResult(DecisionResult.Status.BASELINE, request.baseline(), null, "rule-v1");
    }

    private static boolean continuable(DecisionFacts facts) {
        Map<String, Boolean> f = facts.flags(); Map<String, Double> n = facts.numbers();
        if (!facts.evidence().isEmpty() || !f.keySet().equals(SCHEMA.flags().keySet())
                || !n.keySet().equals(SCHEMA.numbers().keySet())) return false;
        for (String required : Set.of("GOAL_PRESENT", "STATUS_KNOWN", "ACTIVE", "PERSISTENT_KNOWN",
                "AUTO_FOLLOWUP_KNOWN", "AUTO_FOLLOWUP", "USAGE_KNOWN", "CRITERIA_PRESENT", "CRITERIA_VALID", "EVALUATION_SOURCE_VALID", "COOLDOWN_CONFIG_KNOWN", "COOLDOWN_KNOWN", "COOLDOWN_READY")) {
            if (!yes(f, required)) return false;
        }
        if (yes(f, "COOLDOWN_ACTIVE")) return false;
        if (n.values().stream().anyMatch(v -> v < 0 || v != Math.rint(v))) return false;
        for (String counter : Set.of("TURNS_USED", "AGENT_LLM_CALLS_USED", "EVAL_LLM_CALLS_USED", "TURN_BUDGET", "LLM_BUDGET", "COOLDOWN_SECONDS")) {
            if (n.get(counter) > Integer.MAX_VALUE) return false;
        }
        if (n.get("LLM_CALLS_USED") != n.get("AGENT_LLM_CALLS_USED") + n.get("EVAL_LLM_CALLS_USED")) return false;
        double count = n.get("CRITERIA_COUNT"), verified = n.get("VERIFIED_CRITERIA_COUNT");
        if (count > 1000 || verified > count || yes(f, "CRITERIA_EMPTY") != (count == 0)) return false;
        boolean persistent = yes(f, "PERSISTENT");
        if (!budget("TURN", n.get("TURNS_USED"), persistent, f, n)
                || !budget("LLM", n.get("LLM_CALLS_USED"), persistent, f, n)) return false;
        if (!yes(f, "EVALUATION_ACTUAL")) return EVALUATION_FLAGS.stream().noneMatch(key -> yes(f, key));
        return yes(f, "EVALUATION_VALID") && yes(f, "EVALUATION_CONTINUE")
                && !yes(f, "EVALUATION_COMPLETED") && !yes(f, "EVALUATION_FALLBACK")
                && yes(f, "EVALUATION_CURRENT") != yes(f, "EVALUATION_STALE");
    }

    private static boolean budget(String name, double used, boolean persistent,
                                  Map<String, Boolean> f, Map<String, Double> n) {
        double limit = n.get(name + "_BUDGET");
        if (!yes(f, name + "_BUDGET_KNOWN")
                || yes(f, name + "_BUDGET_UNLIMITED") != (persistent && limit == 0)
                || yes(f, name + "_BUDGET_FINITE") != (limit > 0)) return false;
        if (persistent && limit == 0) return true;
        double domainLimit = persistent ? limit : "TURN".equals(name) ? limit - 1 : (int) (limit * 0.9);
        return limit > 0 && used < domainLimit;
    }
    private static boolean yes(Map<String, Boolean> flags, String name) { return Boolean.TRUE.equals(flags.get(name)); }
}
