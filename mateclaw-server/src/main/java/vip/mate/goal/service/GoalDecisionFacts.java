package vip.mate.goal.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.decision.api.DecisionFacts;
import vip.mate.goal.model.*;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, immutable policy snapshot. No goal, criterion, evidence, or evaluator text leaves this boundary. */
public final class GoalDecisionFacts {
    public static final String VERSION = "goal-continuation-v2";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> CRITERION_FIELDS = Set.of("id", "text", "passed", "evidence");
    private static final int MAX_CRITERIA_JSON = 65_536;
    private static final int MAX_CRITERIA = 1_000;
    private GoalDecisionFacts() {}

    /** Compatibility callers have no authoritative scheduling time, so cooldown readiness remains unknown. */
    public static DecisionFacts snapshot(GoalEntity goal, GoalEvaluationResult result) {
        return snapshot(goal, result, null);
    }

    public static DecisionFacts snapshot(GoalEntity goal, GoalEvaluationResult result, LocalDateTime now) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Map<String, Double> numbers = new LinkedHashMap<>();
        boolean persistent = goal != null && Boolean.TRUE.equals(goal.getPersistentExecution());
        flags.put("GOAL_PRESENT", goal != null);
        flags.put("STATUS_KNOWN", goal != null && goal.getStatus() != null);
        flags.put("ACTIVE", goal != null && goal.getStatus() == GoalStatus.ACTIVE);
        flags.put("PERSISTENT_KNOWN", goal != null && goal.getPersistentExecution() != null);
        flags.put("PERSISTENT", persistent);
        flags.put("AUTO_FOLLOWUP_KNOWN", goal != null && goal.getAutoFollowupEnabled() != null);
        flags.put("AUTO_FOLLOWUP", goal != null && Boolean.TRUE.equals(goal.getAutoFollowupEnabled()));
        flags.put("JSON_ACCEPTANCE_REQUIRED", goal != null && goal.isJsonAcceptanceRequired());
        Integer turns = goal == null ? null : goal.getTurnsUsed();
        Integer agentCalls = goal == null ? null : goal.getAgentLlmCallsUsed();
        Integer evalCalls = goal == null ? null : goal.getEvalLlmCallsUsed();
        flags.put("USAGE_KNOWN", nonnegative(turns) && nonnegative(agentCalls) && nonnegative(evalCalls));
        numbers.put("TURNS_USED", value(turns));
        numbers.put("AGENT_LLM_CALLS_USED", value(agentCalls));
        numbers.put("EVAL_LLM_CALLS_USED", value(evalCalls));
        numbers.put("LLM_CALLS_USED", (double) ((long) (agentCalls == null ? 0 : agentCalls) + (evalCalls == null ? 0 : evalCalls)));
        budget("TURN", goal == null ? null : goal.getTurnBudget(), persistent, flags, numbers);
        budget("LLM", goal == null ? null : goal.getLlmCallBudget(), persistent, flags, numbers);
        criteria(goal == null ? null : goal.getCriteria(), flags, numbers);
        cooldown(goal, now, flags, numbers);
        // Durable scheduling uses synthetic results with no evaluator model/calls. They are not verdicts.
        boolean actual = result != null && result.evaluatorModel() != null && !result.evaluatorModel().isBlank()
                && result.llmCallsConsumed() > 0;
        boolean uncalledContinuation = result != null && (result.evaluatorModel() == null || result.evaluatorModel().isBlank())
                && result.llmCallsConsumed() == 0 && !result.completed()
                && GoalEvaluationResult.DECISION_CONTINUE.equals(result.decision())
                && Double.isFinite(result.score()) && result.score() >= 0 && result.score() <= 1
                && result.evaluationRevision() >= 0;
        flags.put("EVALUATION_SOURCE_VALID", result == null || actual || uncalledContinuation);
        boolean valid = actual && goal != null && result.evaluationRevision() >= 0 && goal.getEvaluationRevision() >= 0
                && Double.isFinite(result.score()) && result.score() >= 0 && result.score() <= 1
                && (GoalEvaluationResult.DECISION_CONTINUE.equals(result.decision()) && !result.completed()
                    || GoalEvaluationResult.DECISION_COMPLETED.equals(result.decision()) && result.completed()
                    || GoalEvaluationResult.DECISION_FALLBACK.equals(result.decision()) && !result.completed());
        flags.put("EVALUATION_ACTUAL", actual);
        flags.put("EVALUATION_VALID", valid);
        flags.put("EVALUATION_CURRENT", valid && result.evaluationRevision() == goal.getEvaluationRevision());
        flags.put("EVALUATION_STALE", valid && result.evaluationRevision() != goal.getEvaluationRevision());
        flags.put("EVALUATION_CONTINUE", valid && GoalEvaluationResult.DECISION_CONTINUE.equals(result.decision()));
        flags.put("EVALUATION_COMPLETED", valid && result.completed());
        flags.put("EVALUATION_FALLBACK", valid && GoalEvaluationResult.DECISION_FALLBACK.equals(result.decision()));
        return new DecisionFacts(flags, numbers);
    }

    private static boolean nonnegative(Integer value) { return value != null && value >= 0; }
    private static double value(Integer value) { return value == null ? 0 : value.doubleValue(); }
    private static void budget(String name, Integer budget, boolean persistent,
                               Map<String, Boolean> flags, Map<String, Double> numbers) {
        flags.put(name + "_BUDGET_KNOWN", nonnegative(budget));
        flags.put(name + "_BUDGET_UNLIMITED", budget != null && budget == 0 && persistent);
        flags.put(name + "_BUDGET_FINITE", budget != null && budget > 0);
        numbers.put(name + "_BUDGET", value(budget));
    }

    private static void cooldown(GoalEntity goal, LocalDateTime now,
                                 Map<String, Boolean> flags, Map<String, Double> numbers) {
        Integer seconds = goal == null ? null : goal.getFollowupCooldownSeconds();
        LocalDateTime last = goal == null ? null : goal.getLastFollowupAt();
        boolean configured = nonnegative(seconds);
        boolean known = configured && now != null;
        boolean active = false;
        if (known && seconds > 0 && last != null) {
            try {
                active = last.plusSeconds(seconds).isAfter(now);
            } catch (DateTimeException ignored) {
                known = false;
            }
        }
        flags.put("COOLDOWN_CONFIG_KNOWN", configured);
        flags.put("LAST_FOLLOWUP_PRESENT", last != null);
        flags.put("COOLDOWN_KNOWN", known);
        flags.put("COOLDOWN_ACTIVE", known && active);
        flags.put("COOLDOWN_READY", known && !active);
        numbers.put("COOLDOWN_SECONDS", value(seconds));
    }

    private static boolean hasUnknownFields(JsonNode node) {
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!CRITERION_FIELDS.contains(fields.next())) return true;
        return false;
    }

    private static void criteria(String json, Map<String, Boolean> flags, Map<String, Double> numbers) {
        boolean present = json != null;
        boolean valid = false;
        int count = 0, verified = 0;
        if (present && !json.isBlank() && json.length() <= MAX_CRITERIA_JSON) {
            try {
                JsonNode array = JSON.readTree(json);
                valid = array != null && array.isArray() && array.size() <= MAX_CRITERIA;
                var ids = new HashSet<String>();
                if (valid) for (JsonNode node : array) {
                    if (!node.isObject() || hasUnknownFields(node) || !node.path("id").isTextual() || node.path("id").asText().isBlank()
                            || !ids.add(node.path("id").asText()) || !node.path("text").isTextual()
                            || node.path("text").asText().isBlank() || !node.path("passed").isBoolean()
                            || node.has("evidence") && !node.path("evidence").isNull() && !node.path("evidence").isTextual()) {
                        valid = false; break;
                    }
                }
                if (valid) {
                    var criteria = GoalCriteriaCodec.parse(json, JSON);
                    count = criteria.size();
                    verified = (int) criteria.stream().filter(c -> c.passed() && c.evidence() != null && !c.evidence().isBlank()).count();
                }
            } catch (Exception ignored) { /* Unknown, without logging private JSON or parse diagnostics. */ }
        }
        flags.put("CRITERIA_PRESENT", present);
        flags.put("CRITERIA_VALID", valid);
        flags.put("CRITERIA_EMPTY", valid && count == 0);
        numbers.put("CRITERIA_COUNT", (double) count);
        numbers.put("VERIFIED_CRITERIA_COUNT", (double) verified);
    }
}
