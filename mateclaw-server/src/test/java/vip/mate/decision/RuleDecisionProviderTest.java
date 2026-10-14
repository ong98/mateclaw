package vip.mate.decision;

import org.junit.jupiter.api.Test;
import vip.mate.decision.api.*;
import vip.mate.decision.provider.*;
import vip.mate.goal.model.*;
import vip.mate.goal.service.GoalDecisionFacts;

import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class RuleDecisionProviderTest {
    final LocalDateTime now = LocalDateTime.of(2026, 10, 14, 12, 0);
    final RuleDecisionProvider provider = new RuleDecisionProvider();
    GoalEntity goal() {
        var g = new GoalEntity(); g.setStatus(GoalStatus.ACTIVE); g.setPersistentExecution(true);
        g.setAutoFollowupEnabled(true); g.setTurnBudget(0); g.setLlmCallBudget(0);
        g.setFollowupCooldownSeconds(0); g.setTurnsUsed(0); g.setAgentLlmCallsUsed(0); g.setEvalLlmCallsUsed(0); g.setCriteria("[]");
        return g;
    }
    DecisionRequest request(DecisionFacts facts, String version, boolean retryLegal) {
        var options = new java.util.ArrayList<DecisionQuestion.Option>();
        options.add(new DecisionQuestion.Option("CONTINUE", "continue"));
        if (retryLegal) options.add(new DecisionQuestion.Option("RETRY", "retry"));
        return new DecisionRequest(DecisionType.GOAL_CONTINUATION, new DecisionScope(null, null, null, null, null),
                "GRAPH", new DecisionQuestion.Choice(version, "scheduling", options), facts,
                new DecisionValue.Choice("CONTINUE"), null, null);
    }
    DecisionResult result(GoalEntity goal, GoalEvaluationResult evaluation) {
        return provider.decide(request(GoalDecisionFacts.snapshot(goal, evaluation, now), GoalDecisionFacts.VERSION, true));
    }
    void baseline(GoalEntity goal) {
        var result = result(goal, null);
        assertEquals(DecisionResult.Status.BASELINE, result.status()); assertNull(result.confidence());
    }
    @Test void supportedFactsProduceDeterministicPolicyScoreAndSyntheticResultHasNoVerdict() {
        var synthetic = new GoalEvaluationResult(0, "private gap", "continue", false, "", 0, 0, List.of(), null);
        var facts = GoalDecisionFacts.snapshot(goal(), synthetic, now);
        assertFalse(facts.flags().get("EVALUATION_CURRENT")); assertFalse(facts.flags().get("EVALUATION_ACTUAL"));
        var result = provider.decide(request(facts, GoalDecisionFacts.VERSION, true));
        assertEquals(DecisionResult.Status.PROPOSED, result.status()); assertEquals(1d, result.confidence());
        assertEquals(new DecisionValue.Choice("CONTINUE"), result.value());
        assertThrows(UnsupportedOperationException.class, () -> facts.flags().put("ACTIVE", false));
    }
    @Test void unknownVersionMissingAndInconsistentFactsRemainBaselineWithoutConfidence() {
        var facts = GoalDecisionFacts.snapshot(goal(), null, now);
        assertNull(provider.decide(request(facts, "goal-continuation-v1", true)).confidence());
        assertNull(provider.decide(request(facts, "goal-continuation-v3", true)).confidence());
        var flags = new LinkedHashMap<>(facts.flags()); flags.remove("ACTIVE");
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(new DecisionFacts(flags, facts.numbers()), GoalDecisionFacts.VERSION, true)).status());
        flags = new LinkedHashMap<>(facts.flags()); flags.put("EVALUATION_STALE", true);
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(new DecisionFacts(flags, facts.numbers()), GoalDecisionFacts.VERSION, true)).status());
        var numbers = new LinkedHashMap<>(facts.numbers()); numbers.put("LLM_CALLS_USED", 1d);
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(new DecisionFacts(facts.flags(), numbers), GoalDecisionFacts.VERSION, true)).status());
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(new DecisionFacts(facts.flags(), facts.numbers(), List.of("untrusted")), GoalDecisionFacts.VERSION, true)).status());
    }
    @Test void otherDecisionTypesAndCompletedOrFallbackVerdictsRetainBaseline() {
        var facts = GoalDecisionFacts.snapshot(goal(), null, now);
        var original = request(facts, GoalDecisionFacts.VERSION, true);
        for (var type : List.of(DecisionType.AGENT_ROUTING, DecisionType.WORKER_RESULT)) {
            var other = new DecisionRequest(type, original.scope(), original.phase(), original.question(), facts,
                    original.baseline(), null, null);
            var result = provider.decide(other);
            assertEquals(DecisionResult.Status.BASELINE, result.status()); assertNull(result.confidence());
        }
        for (var evaluation : List.of(
                new GoalEvaluationResult(1, "private", "completed", true, "model", 1, 0, List.of(), null),
                GoalEvaluationResult.fallbackAfterCall("private", "model", 1))) {
            assertEquals(DecisionResult.Status.BASELINE, result(goal(), evaluation).status());
        }
    }

    @Test void actualRevisionMismatchRetriesOnlyWhenRetryIsLegal() {
        var g = goal(); g.setEvaluationRevision(2);
        var evaluation = new GoalEvaluationResult(0, "gap", "continue", false, "model", 1, 0, List.of(), null, 1);
        var facts = GoalDecisionFacts.snapshot(g, evaluation, now);
        assertEquals(new DecisionValue.Choice("RETRY"), result(g, evaluation).value());
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(facts, GoalDecisionFacts.VERSION, false)).status());
        g.setEvaluationRevision(1);
        assertTrue(facts.flags().get("EVALUATION_STALE")); // immutable captured revision relationship
        assertEquals(new DecisionValue.Choice("CONTINUE"), result(g, evaluation).value());
    }
    @Test void malformedUnknownEmptyAndOversizedCriteriaRemainDistinct() {
        for (String json : new String[]{null, "", "null", "not json", "[null]", "[] []", "[{\"id\":\"C1\",\"text\":\"private\",\"passed\":false,\"passed\":true}]", "[{\"id\":\"C1\",\"text\":\"private\",\"passed\":false,\"secret\":\"private\"}]", " ".repeat(65537)}) {
            var g = goal(); g.setCriteria(json); var facts = GoalDecisionFacts.snapshot(g, null, now);
            assertFalse(facts.flags().get("CRITERIA_VALID"), "json=" + json);
            assertFalse(facts.flags().get("CRITERIA_EMPTY")); baseline(g);
        }
        assertTrue(GoalDecisionFacts.snapshot(goal(), null, now).flags().get("CRITERIA_EMPTY"));
    }
    @Test void budgetConfigurationAndUsageBoundariesCannotProposeDelays() {
        var g = goal(); g.setTurnBudget(2); g.setLlmCallBudget(3); g.setTurnsUsed(1); g.setAgentLlmCallsUsed(1); g.setEvalLlmCallsUsed(1);
        assertEquals(DecisionResult.Status.PROPOSED, result(g, null).status());
        g.setTurnsUsed(2); baseline(g); g.setTurnsUsed(1); g.setEvalLlmCallsUsed(2); baseline(g);
        g.setTurnBudget(null); baseline(g); g.setTurnBudget(-1); baseline(g);
        g = goal(); g.setLlmCallBudget(null); baseline(g); g = goal(); g.setTurnsUsed(null); baseline(g);
        g = goal(); g.setPersistentExecution(null); baseline(g); g = goal(); g.setAutoFollowupEnabled(false); baseline(g);
        g = goal(); g.setStatus(GoalStatus.PAUSED); baseline(g);
        g = goal(); g.setLlmCallBudget(Integer.MAX_VALUE); g.setAgentLlmCallsUsed(Integer.MAX_VALUE); g.setEvalLlmCallsUsed(Integer.MAX_VALUE); baseline(g);
        g = goal(); g.setPersistentExecution(false); baseline(g); // zero does not mean unlimited for legacy goals
        g.setTurnBudget(3); g.setLlmCallBudget(10); g.setTurnsUsed(1); g.setAgentLlmCallsUsed(8);
        assertEquals(DecisionResult.Status.PROPOSED, result(g, null).status());
        g.setAgentLlmCallsUsed(9); baseline(g);
    }
    @Test void partialOrMalformedEvaluatorProvenanceDoesNotManufacturePolicyConfidence() {
        for (var evaluation : List.of(
                new GoalEvaluationResult(0, "gap", "continue", false, "model", 0, 0, List.of(), null),
                new GoalEvaluationResult(0, "gap", "continue", false, "", 1, 0, List.of(), null),
                new GoalEvaluationResult(0, "gap", "continue", false, "", -1, 0, List.of(), null),
                new GoalEvaluationResult(Double.NaN, "gap", "continue", false, "", 0, 0, List.of(), null))) {
            var result = result(goal(), evaluation);
            assertEquals(DecisionResult.Status.BASELINE, result.status()); assertNull(result.confidence());
        }
    }

    @Test void cooldownSnapshotRequiresAuthoritativeTimeAndExactDomainBoundary() {
        var g = goal(); g.setFollowupCooldownSeconds(30); g.setLastFollowupAt(now);
        for (int seconds : new int[]{29, 30, 31}) {
            var facts = GoalDecisionFacts.snapshot(g, null, now.plusSeconds(seconds));
            assertEquals(Boolean.TRUE, facts.flags().get("COOLDOWN_KNOWN"));
            assertEquals(seconds < 30, facts.flags().get("COOLDOWN_ACTIVE"));
            assertEquals(seconds >= 30, facts.flags().get("COOLDOWN_READY"));
            assertEquals(seconds < 30 ? DecisionResult.Status.BASELINE : DecisionResult.Status.PROPOSED,
                    provider.decide(request(facts, GoalDecisionFacts.VERSION, true)).status());
        }
        var captured = GoalDecisionFacts.snapshot(g, null, now.plusSeconds(29));
        g.setLastFollowupAt(null);
        assertEquals(Boolean.TRUE, captured.flags().get("COOLDOWN_ACTIVE"));
        assertEquals(DecisionResult.Status.PROPOSED, result(g, null).status());
        g.setFollowupCooldownSeconds(0); g.setLastFollowupAt(now.plusDays(1));
        assertEquals(DecisionResult.Status.PROPOSED, result(g, null).status());
        var unknownTime = GoalDecisionFacts.snapshot(g, null);
        assertEquals(Boolean.FALSE, unknownTime.flags().get("COOLDOWN_KNOWN"));
        assertNull(provider.decide(request(unknownTime, GoalDecisionFacts.VERSION, true)).confidence());
        g.setFollowupCooldownSeconds(null); baseline(g);
        g.setFollowupCooldownSeconds(-1); baseline(g);
        g.setFollowupCooldownSeconds(1); g.setLastFollowupAt(LocalDateTime.MAX);
        baseline(g);
    }

    @Test void missingOrInconsistentCooldownFactsCannotEarnPolicyConfidence() {
        var facts = GoalDecisionFacts.snapshot(goal(), null, now);
        var flags = new LinkedHashMap<>(facts.flags()); flags.remove("COOLDOWN_READY");
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(new DecisionFacts(flags, facts.numbers()), GoalDecisionFacts.VERSION, true)).status());
        flags = new LinkedHashMap<>(facts.flags()); flags.put("COOLDOWN_ACTIVE", true);
        assertEquals(DecisionResult.Status.BASELINE, provider.decide(request(new DecisionFacts(flags, facts.numbers()), GoalDecisionFacts.VERSION, true)).status());
        assertFalse(facts.toString().contains(now.toString()));
    }

    @Test void verifiedCountUsesCanonicalEvidencePresenceWithoutPublishingTexts() {
        var g = goal(); g.setCriteria("[{\"id\":\"C1\",\"text\":\"private\",\"passed\":true,\"evidence\":\"private\"},{\"id\":\"C2\",\"text\":\"private\",\"passed\":true,\"evidence\":\" \"}]");
        var facts = GoalDecisionFacts.snapshot(g, null, now);
        assertEquals(2d, facts.numbers().get("CRITERIA_COUNT")); assertEquals(1d, facts.numbers().get("VERIFIED_CRITERIA_COUNT"));
        assertTrue(facts.evidence().isEmpty()); assertFalse(facts.toString().contains("private"));
    }
}
