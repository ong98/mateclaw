package vip.mate.goal.service;

import org.springframework.stereotype.Service;
import vip.mate.decision.api.DecisionFacts;
import vip.mate.decision.api.DecisionMode;
import vip.mate.decision.api.DecisionOutcome;
import vip.mate.decision.api.DecisionQuestion;
import vip.mate.decision.api.DecisionRequest;
import vip.mate.decision.api.DecisionScope;
import vip.mate.decision.api.DecisionTicket;
import vip.mate.decision.api.DecisionType;
import vip.mate.decision.api.DecisionValue;
import vip.mate.decision.core.DecisionService;
import vip.mate.goal.model.GoalContinuationDecision;
import vip.mate.goal.model.GoalContinuationDecision.Action;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Restricts continuation proposals to scheduling; completion remains a domain transition. */
@Service
public class GoalDecisionAdapter {
    private final GoalFollowupService followups;
    private final DecisionService decisions;

    public GoalDecisionAdapter(GoalFollowupService followups, DecisionService decisions) {
        this.followups = followups;
        this.decisions = decisions;
    }

    public boolean enabled() { return decisions.enabled(DecisionType.GOAL_CONTINUATION); }

    /** Runtime blockers are deterministic and cannot be overridden by a provider. */
    public DecisionTicket guardStop(GoalEntity goal, String reasonCode) {
        return request(goal, Action.DISABLED, null, reasonCode, true);
    }

    public record Selection(DecisionTicket ticket, GoalContinuationDecision decision) {}

    public Selection select(GoalEntity goal, GoalEvaluationResult result, LocalDateTime now,
                            String attemptId, String phase) {
        GoalContinuationDecision baseline = followups.decide(goal, result, now);
        DecisionTicket ticket = request(goal, baseline.action(), attemptId, phase,
                baseline.action() != Action.CONTINUE, result, now);
        return effective(ticket, baseline, now);
    }

    private Selection effective(DecisionTicket ticket, GoalContinuationDecision baseline, LocalDateTime now) {
        if (ticket.mode() != DecisionMode.ACTIVE || baseline.action() != Action.CONTINUE) {
            return new Selection(ticket, baseline);
        }
        String code = ((DecisionValue.Choice) ticket.effectiveValue()).code();
        if ("DEFER".equals(code) || "RETRY".equals(code)) {
            return new Selection(ticket, new GoalContinuationDecision(Action.valueOf(code), baseline.prompt(),
                    now.plusSeconds(30), "decision_" + code.toLowerCase(Locale.ROOT)));
        }
        return new Selection(ticket, baseline);
    }

    public Selection selectGraph(GoalEntity goal, Optional<String> followup, LocalDateTime now, boolean capped) {
        return selectGraph(goal, followup, now, capped, null);
    }

    public Selection selectGraph(GoalEntity goal, Optional<String> followup, LocalDateTime now, boolean capped,
                                 GoalEvaluationResult result) {
        GoalContinuationDecision baseline = followup.isPresent()
                ? new GoalContinuationDecision(Action.CONTINUE, followup.get(), now, "remaining_criteria")
                : new GoalContinuationDecision(Action.DISABLED, null, null, "no_followup");
        if (capped) return new Selection(request(goal, Action.DISABLED, null, "GRAPH_CAP", true), baseline);
        return effective(request(goal, baseline.action(), null, "GRAPH", baseline.action() != Action.CONTINUE, result, now), baseline, now);
    }

    private DecisionTicket request(GoalEntity goal, Action action, String attemptId, String phase, boolean guarded) {
        return request(goal, action, attemptId, phase, guarded, null, null);
    }

    private DecisionTicket request(GoalEntity goal, Action action, String attemptId, String phase, boolean guarded,
                                   GoalEvaluationResult result, LocalDateTime now) {
        DecisionValue.Choice baseline = new DecisionValue.Choice(action.name());
        List<DecisionQuestion.Option> options = guarded
                ? List.of(new DecisionQuestion.Option(action.name(), "Existing domain guard"))
                : List.of(new DecisionQuestion.Option("CONTINUE", "Start the next bounded continuation"),
                        new DecisionQuestion.Option("DEFER", "Defer for thirty seconds"),
                        new DecisionQuestion.Option("RETRY", "Retry scheduling after thirty seconds"));
        DecisionScope scope = new DecisionScope(goal == null ? null : goal.getWorkspaceId(),
                goal == null ? null : goal.getId(), null,
                identifier(goal == null ? null : goal.getConversationId()), identifier(attemptId));
        DecisionFacts facts = GoalDecisionFacts.snapshot(goal, result, now);
        return decisions.decide(new DecisionRequest(DecisionType.GOAL_CONTINUATION, scope, phase,
                new DecisionQuestion.Choice(GoalDecisionFacts.VERSION, "Schedule bounded goal continuation", options),
                facts, baseline, guarded ? baseline : null, null));
    }

    private static String identifier(String value) {
        return value == null || value.isBlank() || value.length() > 128 ? null : value;
    }

    public void outcome(DecisionTicket ticket, boolean applied, String actualCode) {
        DecisionOutcome outcome = !applied ? DecisionOutcome.NOT_APPLIED
                : ticket != null && ticket.mode() == DecisionMode.SHADOW ? DecisionOutcome.OBSERVED : DecisionOutcome.APPLIED;
        decisions.recordOutcome(ticket, outcome, new DecisionValue.Choice(actualCode));
    }

    /** Called only after the existing domain transition succeeds, within its transaction. */
    public void observeTransition(GoalEntity goal, Action action) {
        DecisionTicket ticket = request(goal, action, null, "TERMINAL", true);
        decisions.recordOutcome(ticket, DecisionOutcome.OBSERVED, new DecisionValue.Choice(goal.getStatus().name()));
    }
}
