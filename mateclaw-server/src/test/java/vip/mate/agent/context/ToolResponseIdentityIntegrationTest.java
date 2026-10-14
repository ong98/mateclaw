package vip.mate.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.agent.graph.node.ActionNode;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.config.ConversationWindowProperties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** 验证真实桥接执行后的工具身份、协议配对及所有压缩阶段的身份传播。 */
class ToolResponseIdentityIntegrationTest {
    /** 读取不递归归档，技能和契约在各阶段保留正文，业务响应保留真实身份。 */
    @Test
    void bridgeIdentitySurvivesEveryCompactionPhase(@TempDir Path root) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(root.toString());
        var storage = new ToolResultStorage(properties);
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        manager.setToolResultStorage(storage);
        for (String actual : List.of("read_file", "readSkillFile", "tool_describe", "query_data")) {
            String body = "原始完整证据".repeat(actual.equals("tool_describe") ? 1000 : 2500);
            var response = execute(root, storage, actual, "call-" + actual, body);
            var recent = execute(root, storage, "query_data", "recent", "最近短结果");
            long stored = storage.getSpillCount();
            List<Message> aged = manager.compactAgedToolResponses(List.of(response, recent), 1, "bridge", root.toString());
            aged = manager.pruneOldToolResultsForModelInput(aged, "bridge", root.toString());
            var original = response.getResponses().getFirst();
            var agedResponse = ((ToolResponseMessage) aged.getFirst()).getResponses().getFirst();
            assertEquals(original.name(), agedResponse.name());
            assertEquals(actual, ToolResponseIdentity.name((ToolResponseMessage) aged.getFirst(), agedResponse));
            if (!actual.equals("query_data")) {
                assertEquals(body, agedResponse.responseData());
                assertEquals(stored, storage.getSpillCount(), "读取与规范不能递归归档");
            }
            for (int phase = 0; phase < 4; phase++) {
                var messages = new ArrayList<Message>(List.of(response));
                switch (phase) {
                    case 0 -> manager.softTrimToolResults(messages);
                    case 1 -> manager.hardClearToolResults(messages);
                    case 2 -> manager.prePruneForSummary(messages);
                    case 3 -> manager.spillEvictToolResults(messages, "bridge", root.toString());
                }
                var transformed = (ToolResponseMessage) messages.getFirst();
                var result = transformed.getResponses().getFirst();
                assertEquals(original.id(), result.id());
                assertEquals(original.name(), result.name());
                assertEquals(actual, ToolResponseIdentity.name(transformed, result));
                if (actual.equals("readSkillFile") || actual.equals("tool_describe")
                        || (actual.equals("read_file") && phase == 3)) assertEquals(body, result.responseData());
            }
        }
    }

    /** 不同参数读取同一正文时仅合并模型副本，真实执行后的协议回应及原历史保持完整。 */
    @Test
    void bridgeReadsDeduplicateContentWithoutChangingProtocol(@TempDir Path root) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(root.toString());
        var storage = new ToolResultStorage(properties);
        var json = new ObjectMapper();
        String content = "1\t完整证据".repeat(500);
        String oldBody = json.writeValueAsString(Map.of("filePath", "source.txt", "startLine", 1, "content", content, "endLine", 50));
        String newBody = json.writeValueAsString(Map.of("filePath", "source.txt", "startLine", 1, "content", content, "endLine", 100));
        var old = execute(root, storage, "read_file", "old", oldBody);
        var recent = execute(root, storage, "read_file", "recent", newBody);
        var projected = ReadResultDeduplicator.project(List.of(old, recent));
        var compacted = (ToolResponseMessage) projected.getFirst();
        assertTrue(compacted.getResponses().getFirst().responseData().contains("recent"));
        assertFalse(compacted.getResponses().getFirst().responseData().contains(content));
        assertEquals("tool_call", compacted.getResponses().getFirst().name());
        assertEquals("read_file", ToolResponseIdentity.name(compacted, compacted.getResponses().getFirst()));
        assertEquals(newBody, ((ToolResponseMessage) projected.getLast()).getResponses().getFirst().responseData());
        assertEquals(oldBody, old.getResponses().getFirst().responseData());
    }

    /** 通过正式桥接入口、真实执行器与 ActionNode 创建回应，不手工伪造身份元数据。 */
    private static ToolResponseMessage execute(Path root, ToolResultStorage storage, String actual, String id, String body) throws Exception {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(actual)
                .description("桥接身份验证").inputSchema("{\"type\":\"object\"}").build());
        when(callback.getToolMetadata()).thenReturn(org.springframework.ai.tool.metadata.ToolMetadata.builder().build());
        when(callback.call(anyString(), any())).thenReturn(body);
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                null, null, null, null, storage);
        var node = new ActionNode(executor);
        node.setToolResultStorage(storage);
        // tool_describe 本身是披露入口，正式执行器禁止从 tool_call 递归调用它。
        String protocol = actual.equals("tool_describe") ? actual : "tool_call";
        var call = new AssistantMessage.ToolCall(id, "function", protocol,
                protocol.equals("tool_call")
                        ? new ObjectMapper().writeValueAsString(Map.of("toolName", actual, "arguments", Map.of())) : "{}");
        var state = new RunState(Map.of(CONVERSATION_ID, "bridge", WORKSPACE_BASE_PATH, root.toString(),
                TOOL_CALLS, List.of(call), MESSAGES, List.of(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
        state.applyPatch(node.completeBatch(state, node.executeBatch(state)));
        List<Message> messages = state.value(MESSAGES, List.of());
        return (ToolResponseMessage) messages.getLast();
    }
}
