package vip.mate.agent.graph.state;

import org.junit.jupiter.api.Test;
import vip.mate.agent.GraphEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证工具执行事实与资料引用不被模型进度或历史裁剪替代。 */
class ActionExecutionLedgerTest {

    @Test
    void oversizedReceiptIdentifiersDoNotInflateAnswerFacts() {
        var ledger = ActionExecutionLedger.fromEvents(java.util.List.of(
                vip.mate.agent.GraphEventPublisher.toolComplete("x".repeat(10000), "y".repeat(10000), "failed", false)));
        assertTrue(ledger.renderAnswerFacts().length() < 2000);
    }

    @Test
    void answerFactsPreserveFailuresWithoutInferringRecoveryFromSameToolSuccess() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("failed-1", "tool_call",
                        "{\"error\":true,\"code\":\"TOOL_ARGUMENTS_INCOMPLETE\"}", false),
                GraphEventPublisher.toolComplete("success-2", "tool_call", "different task succeeded", true)));

        String facts = ledger.renderAnswerFacts();
        assertTrue(facts.contains("failed-1"));
        assertTrue(facts.contains("TOOL_ARGUMENTS_INCOMPLETE"));
        assertTrue(facts.contains("success-2"));
        assertTrue(facts.contains("Do not claim that every execution succeeded"));
        assertTrue(facts.contains("does not establish recovery"));
    }

    @Test
    void successfulOnlyRunPreservesNumericResultsWithoutFailureInstruction() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("success", "execute_code", "{\"k\":3,\"n\":3300,\"sum\":11984445550}", true)));
        assertTrue(ledger.renderAnswerFacts().contains("11984445550"));
        assertTrue(ledger.renderAnswerFacts().contains("Copy numeric values verbatim"));
        assertFalse(ledger.renderAnswerFacts().contains("failed calls occurred"));
        assertEquals("", ActionExecutionLedger.empty().renderAnswerFacts());
    }

    /** 有界展示最近事实，完整数量及早期恢复入口保持准确。 */
    @Test
    void successfulResultSamplesKeepLatestSubstantiveCallsAndExactOmittedCounts() {
        var events = new java.util.ArrayList<GraphEventPublisher.GraphEvent>();
        for (int i = 0; i < 30; i++) {
            events.add(new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                    java.util.Map.of("toolCallId", "call-" + i, "toolName", "execute_code",
                            "result", "value-" + i, "success", true), i));
        }
        events.add(GraphEventPublisher.toolComplete("progress", "progress_update", "do not preserve", true));
        String facts = ActionExecutionLedger.fromEvents(events).renderAnswerFacts();
        assertTrue(facts.contains("value-29"));
        assertFalse(facts.contains("value-0\""));
        assertFalse(facts.contains("do not preserve"));
        assertTrue(facts.contains("\"omittedSuccessfulSubstantiveCallCount\":22"));
    }

    /** 发现调用提供资料复用信息，但不成为业务执行成功证据。 */
    @Test
    void disclosureOnlyRunAddsNoAnswerFacts() {
        var ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load", "load_skill", "skill", true),
                GraphEventPublisher.toolComplete("describe", "tool_describe", "schema", true)));
        assertTrue(ledger.renderAnswerFacts().contains("alreadyReadMaterials"));
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
    }

    @Test
    void successfulBusinessToolSatisfiesActionCompletion() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-1", "schedule_meeting", "created", true)));

        assertTrue(ledger.hasSubstantiveAttempt());
        assertTrue(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(ActionExecutionLedger.Status.SUCCEEDED,
                ledger.receipts().get("call-1").status());
    }

    @Test
    void failedBusinessToolIsAnAttemptButNotSuccessfulEvidence() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-2", "schedule_meeting", "HTTP 500", false)));

        assertTrue(ledger.hasSubstantiveAttempt());
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(ActionExecutionLedger.Status.FAILED,
                ledger.receipts().get("call-2").status());
    }

    @Test
    void disclosureToolsNeverSatisfyActionCompletion() {
        ActionExecutionLedger ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load-1", "load_skill", "skill body", true),
                GraphEventPublisher.toolComplete("enable-1", "enable_tool", "enabled", true)));

        assertFalse(ledger.hasSubstantiveAttempt());
        assertFalse(ledger.hasSuccessfulSubstantiveCall());
        assertEquals(2, ledger.receipts().size());
    }

    @Test
    void mergePreservesReceiptsAcrossIterations() {
        ActionExecutionLedger first = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("load-1", "load_skill", "skill body", true)));
        ActionExecutionLedger second = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("call-3", "schedule_meeting", "created", true)));

        ActionExecutionLedger merged = first.merge(second);

        assertEquals(2, merged.receipts().size());
        assertTrue(merged.hasSuccessfulSubstantiveCall());
    }
}
