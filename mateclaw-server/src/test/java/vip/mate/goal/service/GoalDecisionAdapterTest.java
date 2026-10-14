package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.provider.*;
import vip.mate.decision.record.DecisionRecordStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import vip.mate.decision.api.*;
import vip.mate.decision.core.DecisionService;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalDecisionAdapterTest {
    final GoalFollowupService baseline = new GoalFollowupService(new GoalProperties(), new ObjectMapper());
    final DecisionService decisions = mock(DecisionService.class);
    final GoalDecisionAdapter adapter = new GoalDecisionAdapter(baseline, decisions);
    final LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
    GoalEntity goal() {
        var g = new GoalEntity(); g.setId(1L); g.setWorkspaceId(3L); g.setConversationId("conversation");
        g.setStatus(GoalStatus.ACTIVE); g.setAutoFollowupEnabled(true); g.setPersistentExecution(true);
        g.setFollowupCooldownSeconds(0); g.setTurnBudget(0); g.setLlmCallBudget(0); g.setTitle("objective"); return g;
    }
    GoalEvaluationResult evaluation() {
        return new GoalEvaluationResult(0, "remaining", "continue", false, "", 0, 0, List.of(), null);
    }
    @Test void offAndShadowPreserveEveryBaselineFieldAcrossFixtures() {
        for (DecisionMode mode : List.of(DecisionMode.OFF, DecisionMode.SHADOW)) {
            doAnswer(i -> new DecisionTicket("ticket", mode,
                    ((DecisionRequest)i.getArgument(0)).baseline())).when(decisions).decide(any());
            for (int fixture = 0; fixture < 8; fixture++) {
                var g = goal(); var result = evaluation();
                switch (fixture) {
                    case 1 -> { g.setLastFollowupAt(now); g.setFollowupCooldownSeconds(90); }
                    case 2 -> result = GoalEvaluationResult.fallback("sensitive reason");
                    case 3 -> { g.setTurnBudget(1); g.setTurnsUsed(1); }
                    case 4 -> g.setStatus(GoalStatus.COMPLETED);
                    case 5 -> g.setAutoFollowupEnabled(false);
                    case 6 -> g.setStatus(GoalStatus.PAUSED);
                    case 7 -> result = null;
                }
                assertEquals(baseline.decide(g, result, now), adapter.select(g, result, now, "attempt", "DURABLE").decision());
            }
        }
    }
    @Test void activeOnlyChangesContinueToDomainTimedRetryOrDefer() {
        for (String code : List.of("DEFER", "RETRY")) {
            when(decisions.decide(any())).thenReturn(new DecisionTicket("ticket", DecisionMode.ACTIVE, new DecisionValue.Choice(code)));
            var chosen = adapter.select(goal(), evaluation(), now, "attempt", "DURABLE");
            assertEquals(code, chosen.decision().action().name());
            assertEquals(now.plusSeconds(30), chosen.decision().nextRunAt());
            assertEquals(baseline.decide(goal(), evaluation(), now).prompt(), chosen.decision().prompt());
        }
    }
    @Test void nonContinueBaselineIsGuardedAndScopeContainsWorkspace() {
        when(decisions.decide(any())).thenAnswer(i -> {
            DecisionRequest request = i.getArgument(0);
            assertEquals(3L, request.scope().workspaceId()); assertEquals("attempt", request.scope().attemptId());
            assertEquals(request.baseline(), request.guardValue());
            return new DecisionTicket("ticket", DecisionMode.ACTIVE, request.guardValue());
        });
        var g = goal(); g.setStatus(GoalStatus.PAUSED);
        assertEquals(baseline.decide(g, evaluation(), now), adapter.select(g, evaluation(), now, "attempt", "DURABLE").decision());
    }
    @Test void successfulShadowOutcomeIsObservedAndRejectedOutcomeIsNotApplied() {
        var ticket = new DecisionTicket("ticket", DecisionMode.SHADOW, new DecisionValue.Choice("CONTINUE"));
        adapter.outcome(ticket, true, "RUN_STARTED");
        verify(decisions).recordOutcome(ticket, DecisionOutcome.OBSERVED, new DecisionValue.Choice("RUN_STARTED"));
        adapter.outcome(ticket, false, "FENCE_REJECTED");
        verify(decisions).recordOutcome(ticket, DecisionOutcome.NOT_APPLIED, new DecisionValue.Choice("FENCE_REJECTED"));
    }
    @Test void realPipelineRejectsCompletionAndPermissionProposalsAndFallsBackUnchanged() {
        for (String proposal : List.of("COMPLETE", "BUDGET_LIMITED", "DISABLED", "WAITING_INPUT", "unknown")) {
            var provider = new TestProvider(proposal);
            try (var service = core(provider)) {
                var actual = new GoalDecisionAdapter(baseline, service).select(goal(), evaluation(), now, null, "DURABLE");
                assertEquals(baseline.decide(goal(), evaluation(), now), actual.decision());
                assertEquals(1, provider.calls.get());
            }
        }
    }

    @Test void realPipelineNeverCallsProviderForDomainGuardsOrGraphCapsOrTerminalObservations() {
        var provider = new TestProvider("CONTINUE");
        try (var service = core(provider)) {
            var actual = new GoalDecisionAdapter(baseline, service);
            var g = goal();
            actual.selectGraph(g, Optional.of("original prompt"), now, true);
            actual.select(null, null, now, null, "DURABLE");
            actual.select(g, null, now, null, "DURABLE");
            g.setLastFollowupAt(now); g.setFollowupCooldownSeconds(30);
            actual.select(g, evaluation(), now, null, "DURABLE");
            g.setStatus(GoalStatus.COMPLETED);
            actual.observeTransition(g, GoalContinuationDecision.Action.COMPLETE);
            g.setStatus(GoalStatus.PAUSED);
            actual.observeTransition(g, GoalContinuationDecision.Action.BUDGET_LIMITED);
            assertEquals(0, provider.calls.get());
        }
    }

    @Test void terminalObservationRecordsActualCommittedStatus() {
        var ticket = new DecisionTicket("ticket", DecisionMode.ACTIVE, new DecisionValue.Choice("BUDGET_LIMITED"));
        when(decisions.decide(any())).thenReturn(ticket);
        var g = goal(); g.setStatus(GoalStatus.PAUSED);
        adapter.observeTransition(g, GoalContinuationDecision.Action.BUDGET_LIMITED);
        verify(decisions).recordOutcome(ticket, DecisionOutcome.OBSERVED, new DecisionValue.Choice("PAUSED"));
    }

    @Test void missingFieldsAndOversizeCorrelationDoNotAlterBaseline() {
        doAnswer(i -> new DecisionTicket(null, DecisionMode.OFF, ((DecisionRequest)i.getArgument(0)).baseline()))
                .when(decisions).decide(any());
        var g = goal(); g.setWorkspaceId(null); g.setConversationId("x".repeat(200));
        assertEquals(baseline.decide(g, null, now), adapter.select(g, null, now, " ", "DURABLE").decision());
        assertEquals(baseline.decide(null, null, now), adapter.select(null, null, now, null, "DURABLE").decision());
    }

    @Test void graphUsesOriginalPlanningSnapshotWithoutRecomputingAtCooldownBoundary() {
        var followups = mock(GoalFollowupService.class);
        doAnswer(i -> new DecisionTicket("ticket", DecisionMode.SHADOW, ((DecisionRequest)i.getArgument(0)).baseline()))
                .when(decisions).decide(any());
        var graph = new GoalDecisionAdapter(followups, decisions);
        assertEquals(GoalContinuationDecision.Action.DISABLED,
                graph.selectGraph(goal(), Optional.empty(), now.plusSeconds(1), false).decision().action());
        assertEquals("original", graph.selectGraph(goal(), Optional.of("original"), now, false).decision().prompt());
        verifyNoInteractions(followups);
    }

    @Test void realOffShadowPairPreservesDomainFixturesEvenWhenProviderDisagreesOrStoreFails() throws Exception {
        for (DecisionMode mode : List.of(DecisionMode.OFF, DecisionMode.SHADOW)) {
            for (boolean storeFails : List.of(false, true)) {
                var config = new DecisionProperties(); config.setMode(mode); config.setProvider("fixture");
                var records = mock(DecisionRecordStore.class);
                var recorded = new CountDownLatch(13);
                doAnswer(invocation -> {
                    recorded.countDown();
                    if (storeFails) throw new IllegalStateException("record unavailable");
                    return null;
                }).when(records).insert(any());
                var provider = new TestProvider("DEFER");
                try (var service = new DecisionService(config, List.of(provider), records, new SimpleMeterRegistry())) {
                    var actual = new GoalDecisionAdapter(baseline, service);
                    for (int fixture = 0; fixture < 13; fixture++) {
                        var g = goal(); var result = evaluation();
                        switch (fixture) {
                            case 1 -> { g.setLastFollowupAt(now); g.setFollowupCooldownSeconds(90); }
                            case 2 -> result = GoalEvaluationResult.fallback("sensitive detail");
                            case 3 -> { g.setTurnBudget(1); g.setTurnsUsed(1); }
                            case 4 -> g.setStatus(GoalStatus.COMPLETED);
                            case 5 -> g.setAutoFollowupEnabled(false);
                            case 6 -> g.setStatus(GoalStatus.PAUSED);
                            case 7 -> result = null;
                            case 8 -> { g.setLlmCallBudget(2); g.setAgentLlmCallsUsed(2); }
                            case 9, 10, 11 -> {
                                g.setCriteria("[{\"id\":\"C1\",\"text\":\"deployed\",\"passed\":true,\"evidence\":\"verified\"}]");
                                result = new GoalEvaluationResult(1, "verified", "completed", true, "", 0, 0, List.of(), null);
                                if (fixture == 9) g.setJsonAcceptanceRequired(true);
                                if (fixture == 10) g.setCriteria("[{\"id\":\"C1\",\"text\":\"deployed\",\"passed\":true}]");
                            }
                            case 12 -> { g.setLlmCallBudget(null); g.setTurnBudget(null); }
                        }
                        assertEquals(baseline.decide(g, result, now), actual.select(g, result, now, "attempt", "DURABLE").decision(),
                                "mode=" + mode + ", storeFails=" + storeFails + ", fixture=" + fixture);
                    }
                    if (mode == DecisionMode.OFF) assertEquals(0, provider.calls.get());
                    else {
                        assertTrue(recorded.await(5, TimeUnit.SECONDS));
                        assertTrue(provider.calls.get() > 0);
                    }
                }
            }
        }
    }

    @Test void versionedFactsAreBoundedPrivateAndSnapshotActualEvaluation() {
        var g = goal(); g.setCriteria("[{\"id\":\"C1\",\"text\":\"private objective\",\"passed\":true,\"evidence\":\"private evidence\"}]");
        g.setAgentLlmCallsUsed(Integer.MAX_VALUE); g.setEvalLlmCallsUsed(Integer.MAX_VALUE);
        g.setEvaluationRevision(4);
        when(decisions.decide(any())).thenAnswer(i -> {
            DecisionRequest r = i.getArgument(0);
            assertEquals("goal-continuation-v2", r.question().version());
            assertEquals(4294967294d, r.facts().numbers().get("LLM_CALLS_USED"));
            assertEquals(1d, r.facts().numbers().get("VERIFIED_CRITERIA_COUNT"));
            assertTrue(r.facts().flags().get("EVALUATION_STALE"));
            assertFalse(r.facts().toString().contains("private"));
            return new DecisionTicket(null, DecisionMode.OFF, r.baseline());
        });
        adapter.select(g,
                new GoalEvaluationResult(0, "private gap", "continue", false, "private model", 1, 0, List.of(), null, 3), now, null, "GRAPH");
    }

    @Test void rulePipelineValidatesCurrentFactsAndRetriesOnlyActualStaleEvaluations() {
        var config = new DecisionProperties(); config.setMode(DecisionMode.ACTIVE); config.setProvider("rule");
        try (var service = new DecisionService(config, List.of(new RuleDecisionProvider()), mock(DecisionRecordStore.class), new SimpleMeterRegistry())) {
            var actual = new GoalDecisionAdapter(baseline, service);
            var g = goal(); g.setCriteria("[]"); g.setTurnsUsed(0); g.setAgentLlmCallsUsed(0); g.setEvalLlmCallsUsed(0); g.setEvaluationRevision(2);
            var stale = new GoalEvaluationResult(0, "gap", "continue", false, "model", 1, 0, List.of(), null, 1);
            assertEquals(GoalContinuationDecision.Action.RETRY, actual.select(g, stale, now, null, "GRAPH").decision().action());
            assertEquals(GoalContinuationDecision.Action.CONTINUE, actual.select(g, evaluation(), now, null, "GRAPH").decision().action());
            assertEquals(GoalContinuationDecision.Action.CONTINUE, actual.select(g, stale.withEvaluationRevision(2), now, null, "GRAPH").decision().action());
            g.setTurnBudget(1); g.setTurnsUsed(1);
            assertEquals(GoalContinuationDecision.Action.BUDGET_LIMITED, actual.select(g, stale, now, null, "GRAPH").decision().action());
        }
    }

    @Test void realRuleRecordsOnlySchedulingMetadataAcrossModesAndKeepsShadowBaseline() throws Exception {
        for (DecisionMode mode : DecisionMode.values()) {
            var config = new DecisionProperties(); config.setMode(mode); config.setProvider("rule");
            var records = mock(DecisionRecordStore.class);
            var inserted = new java.util.concurrent.CopyOnWriteArrayList<vip.mate.decision.record.DecisionRecord>();
            var latch = new CountDownLatch(1);
            doAnswer(i -> { inserted.add(i.getArgument(0)); latch.countDown(); return null; }).when(records).insert(any());
            try (var service = new DecisionService(config, List.of(new RuleDecisionProvider()), records, new SimpleMeterRegistry())) {
                var actual = new GoalDecisionAdapter(baseline, service);
                var g = goal(); g.setCriteria("[]"); g.setTurnsUsed(0); g.setAgentLlmCallsUsed(0); g.setEvalLlmCallsUsed(0); g.setEvaluationRevision(2);
                var stale = new GoalEvaluationResult(0, "private gap", "continue", false, "private model", 1, 0, List.of(), null, 1);
                var selected = actual.selectGraph(g, Optional.of("private prompt"), now, false, stale);
                assertEquals(mode == DecisionMode.ACTIVE ? GoalContinuationDecision.Action.RETRY : GoalContinuationDecision.Action.CONTINUE,
                        selected.decision().action());
                assertEquals("private prompt", selected.decision().prompt());
                if (mode == DecisionMode.OFF) { verifyNoInteractions(records); continue; }
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                var record = inserted.getFirst();
                assertEquals("goal-continuation-v2", record.questionVersion());
                assertEquals("CONTINUE", record.baseline()); assertEquals("RETRY", record.proposed());
                assertEquals("PROPOSED", record.reason()); assertEquals(1d, record.confidence());
                assertFalse(record.toString().contains("private"));
                actual.outcome(selected.ticket(), true, mode == DecisionMode.ACTIVE ? "GRAPH_SUPPRESSED" : "FOLLOWUP_SUBMITTED");
                verify(records, timeout(5000)).outcome(eq(selected.ticket().id()),
                        eq(mode == DecisionMode.SHADOW ? DecisionOutcome.OBSERVED : DecisionOutcome.APPLIED), any());
            }
        }
    }

    @Test void graphCooldownFactsValidateActualNowWithoutChangingOriginalPlanningSnapshot() {
        var config = new DecisionProperties(); config.setMode(DecisionMode.ACTIVE); config.setProvider("rule");
        var records = mock(DecisionRecordStore.class);
        try (var service = new DecisionService(config, List.of(new RuleDecisionProvider()), records, new SimpleMeterRegistry())) {
            var actual = new GoalDecisionAdapter(baseline, service);
            var g = goal(); g.setCriteria("[]"); g.setTurnsUsed(0); g.setAgentLlmCallsUsed(0); g.setEvalLlmCallsUsed(0);
            g.setFollowupCooldownSeconds(30); g.setLastFollowupAt(now); g.setEvaluationRevision(2);
            var stale = new GoalEvaluationResult(0, "private", "continue", false, "model", 1, 0, List.of(), null, 1);
            assertEquals(GoalContinuationDecision.Action.CONTINUE,
                    actual.selectGraph(g, Optional.of("planned"), now.plusSeconds(29), false, stale).decision().action());
            assertEquals(GoalContinuationDecision.Action.RETRY,
                    actual.selectGraph(g, Optional.of("planned"), now.plusSeconds(30), false, stale).decision().action());
            assertEquals(GoalContinuationDecision.Action.DISABLED,
                    actual.selectGraph(g, Optional.empty(), now.plusSeconds(31), false, stale).decision().action());
            var captor = org.mockito.ArgumentCaptor.forClass(vip.mate.decision.record.DecisionRecord.class);
            verify(records, times(3)).insert(captor.capture());
            assertEquals("BASELINE", captor.getAllValues().get(0).reason());
            assertNull(captor.getAllValues().get(0).confidence());
            assertEquals("PROPOSED", captor.getAllValues().get(1).reason());
            assertEquals("GUARD", captor.getAllValues().get(2).reason());
        }
    }

    private DecisionService core(TestProvider provider) {
        var config = new DecisionProperties(); config.setMode(DecisionMode.ACTIVE); config.setProvider("fixture");
        return new DecisionService(config, List.of(provider), mock(DecisionRecordStore.class), new SimpleMeterRegistry());
    }

    private static final class TestProvider implements DecisionProvider {
        private final String proposal;
        private final AtomicInteger calls = new AtomicInteger();
        TestProvider(String proposal) { this.proposal = proposal; }
        public String id() { return "fixture"; }
        public DecisionCapability capability() { return DecisionCapability.standard(); }
        public DecisionResult decide(DecisionRequest request) {
            calls.incrementAndGet();
            return DecisionResult.proposed(new DecisionValue.Choice(proposal), 1, "fixture-v1");
        }
    }

}
