package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.tool.builtin.ReadFileTool;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.workspace.core.service.MemberFileIsolation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** 用真实执行节点、执行器和文件读取工具验证领取后长链路的恢复与提交。 */
class ActionReceiptRecoveryIntegrationTest {
    /** 领取退出最近二十四条正文且消息被裁剪后，仍从索引恢复两项并各提交一次。 */
    @Test
    void recoverAfterThirtyEvidenceCallsAndSubmitWithoutReclaim(@TempDir Path directory) throws Exception {
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger submissions = new AtomicInteger();
        String claimResult = "{\"claimed\":[{\"detailId\":7118},{\"detailId\":7119}]}";
        var node = node(directory, callback("claim_case", args -> {
            claims.incrementAndGet();
            return claimResult;
        }), callback("read_evidence", args -> "evidence"), callback("submit_result", args -> {
            assertTrue(args.contains("7118") || args.contains("7119"));
            assertTrue(args.contains("original-request"));
            submissions.incrementAndGet();
            return "{\"accepted\":true}";
        }));
        var state = state(directory);
        execute(node, state, "claim-original", "claim_case", "{\"requestId\":\"original-request\"}");
        for (int i = 0; i < 30; i++) execute(node, state, "evidence-" + i, "read_evidence", "{}");
        var ledger = state.<ActionExecutionLedger>value(ACTION_EXECUTION_LEDGER).orElseThrow();
        String facts = ledger.renderAnswerFacts();
        assertFalse(facts.contains("\"toolCallId\":\"claim-original\""), "领取已退出最近调用正文");
        assertTrue(facts.contains("receiptIndexPath"));
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        state.replace(MESSAGES, manager.compactForRetry(state.value(MESSAGES, List.of())));
        var index = new ObjectMapper().readTree(read(indexPath(facts)));
        var claim = index.path("receipts").get(0);
        assertEquals(31, index.path("receipts").size());
        assertEquals("claim-original", claim.path("toolCallId").asText());
        assertTrue(claim.path("argumentsPreview").asText().contains("original-request"));
        var recovered = new ObjectMapper().readTree(read(claim.path("resultRef").asText()));
        for (var item : recovered.path("claimed")) {
            execute(node, state, "submit-" + item.path("detailId").asInt(), "submit_result",
                    "{\"detailId\":" + item.path("detailId").asInt() + ",\"requestId\":\"original-request\"}");
        }
        assertEquals(1, claims.get());
        assertEquals(2, submissions.get());
        var complete = state.<ActionExecutionLedger>value(ACTION_EXECUTION_LEDGER).orElseThrow();
        assertEquals(33, new ObjectMapper().readTree(read(indexPath(complete.renderAnswerFacts())))
                .path("receipts").size(), "本轮索引随提交更新");

        var nextRun = state(directory);
        execute(node, nextRun, "claim-next-run", "claim_case", "{\"requestId\":\"original-request\"}");
        assertEquals(2, claims.get(), "用户显式新一轮操作正常执行，即使参数相同");
        String nextIndex = indexPath(nextRun.<ActionExecutionLedger>value(ACTION_EXECUTION_LEDGER)
                .orElseThrow().renderAnswerFacts());
        assertNotEquals(indexPath(facts), nextIndex);
        assertEquals(1, new ObjectMapper().readTree(read(nextIndex)).path("receipts").size());
    }

    /** 归档使用执行器已经认证的成员目录，而不是会话中未隔离的工作区。 */
    @Test
    void archiveUsesAuthenticatedMemberRoot(@TempDir Path directory) {
        var storage = mock(ToolResultStorage.class);
        var node = new ActionNode(null);
        node.setToolResultStorage(storage);
        var state = state(directory);
        var member = directory.resolve("member").toString();
        MemberFileIsolation.configure(origin -> ChatOrigin.EMPTY.withWorkspace(1L, member));
        try {
            node.completeBatch(state, new ToolExecutionExecutor.ToolExecutionResult(List.of(),
                    List.of(vip.mate.agent.GraphEventPublisher.toolComplete("read-1", "read_evidence", "value", true)),
                    false, null, null));
            verify(storage).preserveForRecovery("value", "read_evidence", "read-1", "receipt-conversation", member);
            verify(storage).preserveRecoveryIndex(anyString(), eq("read-1"), eq("receipt-conversation"), eq(member));
        } finally {
            MemberFileIsolation.configure(null);
        }
    }

    /** 直接返回工具的原文和参数不能经通用索引重新暴露给模型。 */
    @Test
    void directResultDoesNotExposeArgumentsOrBody(@TempDir Path directory) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(directory.toString());
        var storage = new ToolResultStorage(properties);
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("private_result")
                .description("直接返回验证").inputSchema("{\"type\":\"object\"}").build());
        when(callback.getToolMetadata()).thenReturn(org.springframework.ai.tool.metadata.ToolMetadata.builder()
                .returnDirect(true).build());
        when(callback.call(anyString(), any())).thenReturn("private-body");
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                (name, args) -> ToolGuardResult.allow(), null, null);
        var calls = List.of(new AssistantMessage.ToolCall("direct", "function", "private_result",
                "{\"query\":\"private-arguments\"}"));
        var executed = executor.execute(calls, "receipt-conversation", "receipt-agent", false, "user", directory.toString());
        assertTrue(executed.hasDirectOutputs());
        var events = executed.events();
        var ledger = ActionExecutionLedger.fromEvents(events).preserveResults(events,
                calls,
                storage, "receipt-conversation", directory.toString()).preserveIndex(storage,
                "receipt-conversation", directory.toString());
        var receipt = ledger.receipts().get("direct");
        assertNull(receipt.resultRef());
        assertNull(receipt.argumentsPreview());
        String index = read(indexPath(ledger.renderAnswerFacts()));
        assertFalse(index.contains("private-body"));
        assertFalse(index.contains("private-arguments"));
    }

    /** 将模拟业务回调接入真实执行器并配置通用回执存储。 */
    private static ActionNode node(Path directory, ToolCallback... callbacks) {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(directory.toString());
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callbacks)),
                (name, args) -> ToolGuardResult.allow(), null, null);
        var node = new ActionNode(executor);
        node.setToolResultStorage(new ToolResultStorage(properties));
        return node;
    }

    /** 为同一会话创建新的独立执行轮次，不继承上一轮执行台账。 */
    private static RunState state(Path directory) {
        return new RunState(Map.of(CONVERSATION_ID, "receipt-conversation", AGENT_ID, "receipt-agent",
                WORKSPACE_BASE_PATH, directory.toString()));
    }

    /** 执行一批真实工具并应用结果增量，保持原调用标识。 */
    private static void execute(ActionNode node, RunState state, String id, String tool, String args) throws Exception {
        state.replace(TOOL_CALLS, List.of(new AssistantMessage.ToolCall(id, "function", tool, args)));
        state.applyPatch(node.completeBatch(state, node.executeBatch(state)));
    }

    /** 从有界事实的首个 JSON 行取得独立于历史裁剪的恢复入口。 */
    private static String indexPath(String facts) throws Exception {
        String json = facts.lines().filter(line -> line.startsWith("{")).findFirst().orElseThrow();
        return new ObjectMapper().readTree(json).path("receiptIndexPath").asText();
    }

    /** 用实际 read_file 验证原文可读取，并去掉其行号包装。 */
    private static String read(String path) throws Exception {
        var tool = new ReadFileTool(mock(vip.mate.i18n.I18nService.class));
        var result = new ObjectMapper().readTree(tool.read_file(path, null, null, null, null));
        assertFalse(result.path("error").asBoolean(), result.toString());
        assertFalse(result.path("truncated").asBoolean(), result.toString());
        String content = result.path("content").asText();
        return content.substring(content.indexOf('{')).strip();
    }

    /** 构造有计数和输入校验的业务回调，不访问生产质检服务。 */
    private static ToolCallback callback(String name, Function<String, String> function) {
        return new ToolCallback() {
            /** @return 供真实执行器发现的工具契约 */
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("回执恢复验证")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            /**
             * 执行模拟业务行为。
             * @param input 实际调用参数
             * @return 本次业务回执
             */
            @Override public String call(String input) { return function.apply(input); }
        };
    }
}
