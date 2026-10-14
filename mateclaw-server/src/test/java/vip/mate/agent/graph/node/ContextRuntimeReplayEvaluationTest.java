package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.*;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.*;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.config.ConversationWindowProperties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** 固定通用轨迹回放，测量真实请求装配而排除模型等待，不将模拟结果冒充真实模型收益。 */
class ContextRuntimeReplayEvaluationTest {
    /** 查询、文件与副作用长链路均保持证据、协议配对和操作次数；预热后测三十次分布。 */
    @ParameterizedTest
    @ValueSource(strings = {"query", "files", "workflow"})
    void evaluateFixedTrajectoryWithoutModelLatency(String scenario, @TempDir Path root) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(root.toString());
        var storage = new ToolResultStorage(properties);
        var json = new ObjectMapper();
        var operations = new AtomicInteger();
        var read = callback("read_file");
        var query = callback("query_data");
        var save = callback("save_result");
        var tools = AgentToolSet.fromCallbacks(List.of(), List.of(read, query, save));
        var executor = new ToolExecutionExecutor(tools, null, null, null, null, storage);
        var action = new ActionNode(executor);
        action.setToolResultStorage(storage);
        String conversation = "replay-" + scenario;
        var state = new RunState(Map.of(CONVERSATION_ID, conversation, WORKSPACE_BASE_PATH, root.toString(),
                SYSTEM_PROMPT, "依据真实回执完成任务，不能重放已执行操作。", USER_MESSAGE, "固定轨迹目标", MAX_ITERATIONS, 100,
                MESSAGES, List.of(new UserMessage("固定轨迹目标"))));
        String evidence = "1\t稳定证据正文与原始身份 ORIGINAL_ID\n".repeat(70);
        for (int i = 0; i < 35; i++) {
            String actual = scenario.equals("query") ? "query_data" : "read_file";
            if (scenario.equals("workflow") && (i == 0 || i == 34)) actual = "save_result";
            String body = actual.equals("read_file")
                    ? json.writeValueAsString(Map.of("filePath", "original-evidence.txt", "startLine", 1,
                            "startColumn", 1, "endLine", i + 1, "content", evidence))
                    : json.writeValueAsString(Map.of("originalId", "ORIGINAL_ID", "requestId", "original-request",
                            "status", "SUCCEEDED", "item", i, "evidence", actual.equals("save_result") ? "saved" : evidence));
            doAnswer(inv -> { operations.incrementAndGet(); return body; })
                    .when(actual.equals("read_file") ? read : actual.equals("save_result") ? save : query).call(anyString(), any());
            var call = new AssistantMessage.ToolCall("step-" + i, "function", "tool_call",
                    json.writeValueAsString(Map.of("toolName", actual, "arguments", Map.of("item", i))));
            state.replace(TOOL_CALLS, List.of(call));
            state.applyPatch(Map.of(MESSAGES, List.of(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
            state.applyPatch(action.completeBatch(state, action.executeBatch(state)));
        }
        int executed = operations.get();
        assertEquals(35, executed);
        List<Message> history = state.value(MESSAGES, List.of());
        var ledger = state.<ActionExecutionLedger>value(ACTION_EXECUTION_LEDGER).orElseThrow();
        if (!scenario.equals("files")) assertTrue(Files.readString(Path.of(ledger.receipts().get("step-0").resultRef()))
                .contains("ORIGINAL_ID"), "早期业务回执仍可恢复");
        var window = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        window.setToolResultStorage(storage);
        var helper = mock(NodeStreamingChatHelper.class);
        var captured = new AtomicReference<Prompt>();
        when(helper.streamCall(any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            captured.set(inv.getArgument(1));
            return new NodeStreamingChatHelper.StreamResult("固定响应", "", new AssistantMessage("固定响应"), List.of(), false, 0, 0);
        });
        var reasoning = new ReasoningNode(mock(ChatModel.class), tools, null, false, helper, window, null, 1024, null, null, null, null);
        reasoning.setPrefixBudgetPlan(new PrefixBudgetPlan(true, 128000, PrefixBudgetPlan.Profile.NORMAL, 2000, 0, 0, 0, 0, 600, 1000));
        var samples = new ArrayList<Double>();
        var tokenCounts = new HashSet<Integer>();
        for (int sample = -5; sample < 30; sample++) {
            long started = System.nanoTime();
            reasoning.apply(state.snapshot());
            double durationMs = (System.nanoTime() - started) / 1_000_000.0;
            if (sample >= 0) samples.add(durationMs);
            var prompt = captured.get();
            assertNotNull(prompt);
            tokenCounts.add(TokenEstimator.estimateTokens(prompt.getInstructions()));
            var calls = new HashSet<String>();
            var results = new HashSet<String>();
            boolean fullEvidence = false;
            for (Message message : prompt.getInstructions()) {
                if (message instanceof AssistantMessage assistant) assistant.getToolCalls().forEach(call -> calls.add(call.id()));
                if (message instanceof ToolResponseMessage response) {
                    for (var result : response.getResponses()) {
                        results.add(result.id());
                        assertEquals("tool_call", result.name());
                        if (result.responseData().contains("稳定证据正文")) fullEvidence = true;
                    }
                }
            }
            assertEquals(calls, results);
            assertTrue(fullEvidence);
            assertEquals(history, state.value(MESSAGES, List.of()), "请求装配不能修改原历史");
            assertEquals(executed, operations.get(), "回放请求装配不能执行历史业务工具");
        }
        assertEquals(1, tokenCounts.size(), "固定轨迹的请求 tokens 必须可重复");
        samples.sort(Double::compareTo);
        var metrics = Map.of("scenario", scenario, "samples", 30, "warmups", 5,
                "medianAssemblyMs", (samples.get(14) + samples.get(15)) / 2,
                "p95AssemblyMs", samples.get(28), "estimatedPromptTokens", tokenCounts.iterator().next(),
                "executedToolCalls", executed, "modelWaitMs", 0,
                "scope", "固定通用轨迹的请求装配，含归档；不代表完整任务效率或前后版本收益");
        String report = json.writeValueAsString(metrics);
        Path evidenceRoot = Path.of("../data/local/context-runtime-replay");
        Files.createDirectories(evidenceRoot);
        Files.writeString(evidenceRoot.resolve(scenario + ".json"), report);
        System.out.println("REPLAY_EVALUATION " + report);
    }

    /** 回调只累计真实执行次数，正文由固定轨迹提供，不访问生产业务。 */
    private static ToolCallback callback(String name) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description("固定轨迹回放")
                .inputSchema("{\"type\":\"object\"}").build());
        when(callback.getToolMetadata()).thenReturn(org.springframework.ai.tool.metadata.ToolMetadata.builder().build());
        return callback;
    }
}
