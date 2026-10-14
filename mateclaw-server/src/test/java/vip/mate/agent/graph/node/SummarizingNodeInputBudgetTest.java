package vip.mate.agent.graph.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.progress.ProgressEntry;
import vip.mate.agent.progress.ProgressLedger;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.progress.ProgressStatus;
import vip.mate.agent.runtime.state.RunState;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** 捕获摘要节点真实请求，验证预算、必要事实、原文恢复和失败降级不丢交接入口。 */
class SummarizingNodeInputBudgetTest {
    /** 大观察在模型请求中有界，早期身份和活跃步骤仍可见，原推理正文不变。 */
    @Test
    void boundedRequestKeepsFactsAndOriginalEvidence(@TempDir Path root) throws Exception {
        var helper = helper(false);
        var storage = storage(root);
        var progress = mock(ProgressLedgerService.class);
        when(progress.load("summary-conv")).thenReturn(new ProgressLedger(Map.of("review",
                new ProgressEntry("review", "当前分析", ProgressStatus.IN_PROGRESS,
                        "证据".repeat(90) + "claim-original；请求 request-original；下一步提交", Instant.now())), Map.of()));
        var observations = new ArrayList<String>();
        for (int i = 0; i < 30; i++) observations.add("本轮" + i + "证据".repeat(5000)
                + "MIDDLE-ONLY-" + i + "依据".repeat(5000) + "结论" + i);
        var state = state(root, observations);
        String receipt = "{\"identity\":\"original-731\",\"requestId\":\"request-original\"}";
        var calls = List.of(new AssistantMessage.ToolCall("claim-original", "function", "claim_work", "{}"));
        var events = List.of(GraphEventPublisher.toolComplete("claim-original", "claim_work", receipt, true));
        var ledger = ActionExecutionLedger.fromEvents(events).preserveResults(events, calls, storage,
                "summary-conv", root.toString()).preserveIndex(storage, "summary-conv", root.toString());
        state.replace(ACTION_EXECUTION_LEDGER, ledger);
        var history = List.<Message>of(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("read-original", "read_file", observations.get(0)))).build());
        state.replace(MESSAGES, history);
        var node = new SummarizingNode(mock(ChatModel.class), helper);
        node.configureInput(storage, progress, 12000);
        var output = node.apply(state);
        Prompt sent = sentPrompt(helper);
        String text = sent.getInstructions().get(1).getText();
        assertTrue(TokenEstimator.estimateTokens(sent.getInstructions()) <= 12000);
        assertTrue(text.contains("original-731"));
        assertTrue(text.contains("request-original"));
        assertTrue(text.contains("下一步提交"));
        assertEquals(history, state.value(MESSAGES, List.of()));
        assertEquals(observations, state.value(OBSERVATION_HISTORY, List.of()));
        Path snapshot = snapshotPath(text);
        String full = Files.readString(snapshot);
        for (int i = 0; i < 30; i++) assertTrue(full.contains("MIDDLE-ONLY-" + i));
        var reader = new vip.mate.tool.builtin.ReadFileTool(mock(vip.mate.i18n.I18nService.class));
        String page = reader.read_file(snapshot.toString(), 50, 50, null,
                ChatOrigin.web("summary-conv", "user", 1L, root.toString()).toToolContext());
        var recovered = new com.fasterxml.jackson.databind.ObjectMapper().readTree(page);
        assertFalse(recovered.path("error").asBoolean(), page);
        assertTrue(recovered.path("content").asText().contains("MIDDLE-ONLY-24"));
        assertTrue(String.valueOf(output.get(SUMMARIZED_CONTEXT)).contains(snapshot.toString()));
        assertEquals(receipt, Files.readString(Path.of(ledger.receipts().get("claim-original").resultRef())));
    }

    /** 无法归档时保留全部观察；摘要调用失败也不只保留前部片段。 */
    @Test
    void storageAndModelFailureKeepUnarchivedTail(@TempDir Path root) throws Exception {
        var helper = helper(true);
        var storage = mock(ToolResultStorage.class);
        var observations = List.of("早期" + "证据".repeat(20000) + "末尾唯一失败依据 TAIL-ERROR-17");
        var node = new SummarizingNode(mock(ChatModel.class), helper);
        node.configureInput(storage, null, 5000);
        var output = node.apply(state(root, observations));
        assertTrue(sentPrompt(helper).getInstructions().get(1).getText().contains(observations.get(0)));
        String fallback = String.valueOf(output.get(SUMMARIZED_CONTEXT));
        assertTrue(fallback.contains("TAIL-ERROR-17"));
        assertTrue(fallback.contains("按原目标继续分析"));
        assertFalse(fallback.contains("完整观察快照"));
    }

    /** 完全重复内容保留实际轮次，不合并不同结论，也不为小输入创建新归档。 */
    @Test
    void duplicatesPreserveSequenceAndChangedResult(@TempDir Path root) throws Exception {
        var helper = helper(false);
        var storage = mock(ToolResultStorage.class);
        var node = new SummarizingNode(mock(ChatModel.class), helper);
        node.configureInput(storage, null, 12000);
        node.apply(state(root, List.of("完全相同事实 ABC", "完全相同事实 ABC", "发生变化 XYZ")));
        String text = sentPrompt(helper).getInstructions().get(1).getText();
        assertEquals(1, text.split("完全相同事实 ABC", -1).length - 1);
        assertTrue(text.contains("第 2 轮观察"));
        assertTrue(text.contains("第 1 轮完全相同"));
        assertTrue(text.contains("发生变化 XYZ"));
        verifyNoInteractions(storage);
    }

    /** 新输入使用独立快照，旧观察的内容不会被后续摘要覆盖。 */
    @Test
    void newInputKeepsOldSnapshot(@TempDir Path root) throws Exception {
        var helper = helper(false);
        var node = new SummarizingNode(mock(ChatModel.class), helper);
        node.configureInput(storage(root), null, 5000);
        node.apply(state(root, List.of("旧观察".repeat(10000))));
        Path old = snapshotPath(sentPrompt(helper).getInstructions().get(1).getText());
        byte[] original = Files.readAllBytes(old);
        var next = state(root, List.of("新观察".repeat(10000)));
        next.replace(CHAT_ORIGIN, ChatOrigin.web("summary-conv", "user", 1L, root.toString()).withOriginMessageId(2L));
        node.apply(next);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(helper, times(2)).streamCall(any(), prompts.capture(), anyString(), anyString());
        Path newer = snapshotPath(prompts.getAllValues().get(1).getInstructions().get(1).getText());
        assertNotEquals(old, newer);
        assertArrayEquals(original, Files.readAllBytes(old));
        assertTrue(Files.readString(newer).contains("新观察"));
    }

    /** 构造固定摘要返回，失败场景不提供部分成功内容。 */
    private NodeStreamingChatHelper helper(boolean fatal) {
        var helper = mock(NodeStreamingChatHelper.class);
        var result = fatal ? new NodeStreamingChatHelper.StreamResult("", "", null, List.of(), false, 0, 0,
                false, "provider unavailable", NodeStreamingChatHelper.ErrorType.UNKNOWN)
                : new NodeStreamingChatHelper.StreamResult("已整理的摘要", "", new AssistantMessage("摘要"), List.of(), false, 10, 10);
        when(helper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);
        return helper;
    }

    /** 真实文件存储使用当前测试目录，归档策略与运行时一致。 */
    private ToolResultStorage storage(Path root) {
        var props = new ToolResultProperties();
        props.setStorageBaseDir(root.resolve("receipts").toString());
        return new ToolResultStorage(props);
    }

    /** 构造同输入的真实摘要状态，观察历史由调用方保留原集合。 */
    private RunState state(Path root, List<String> observations) {
        return new RunState(Map.of(CONVERSATION_ID, "summary-conv", USER_MESSAGE, "按原目标继续分析",
                WORKSPACE_BASE_PATH, root.toString(), CHAT_ORIGIN,
                ChatOrigin.web("summary-conv", "user", 1L, root.toString()).withOriginMessageId(1L),
                OBSERVATION_HISTORY, observations, CURRENT_ITERATION, 21));
    }

    /** 从真实流式调用中捕获模型请求，防止只验证辅助预算器。 */
    private Prompt sentPrompt(NodeStreamingChatHelper helper) {
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(helper).streamCall(any(), prompt.capture(), anyString(), anyString());
        return prompt.getValue();
    }

    /** 解析系统提供的快照入口，核对实际保存的完整正文。 */
    private Path snapshotPath(String text) {
        String marker = "[完整观察快照] ";
        int start = text.indexOf(marker) + marker.length();
        assertTrue(text.contains(marker));
        return Path.of(text.substring(start, text.indexOf('\n', start)));
    }
}
