package vip.mate.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.graph.executor.ToolResultProperties;
import static org.junit.jupiter.api.Assertions.*;

/** 用文件分析和模拟执行验证回执、工作集、产物与跨轮恢复的通用能力。 */
class RuntimeContextFoundationTest {
    /** 未配置专属工作区时使用既有默认沙箱，产物提示和检查点仍必须生效。 */
    @Test
    void defaultSandboxSupportsArtifactsAndCheckpoint(@TempDir Path root) throws Exception {
        var previous = vip.mate.tool.guard.WorkspacePathGuard.getDefaultRoot();
        vip.mate.tool.guard.WorkspacePathGuard.setDefaultRoot(root.toString());
        try {
            var origin = ChatOrigin.web("conv-default", "user", 1L, null).withOriginMessageId(1L);
            assertTrue(RuntimeArtifacts.prepare(origin).startsWith(root));
            String prompt = RuntimeContextInjector.buildContextMessage(null, null, origin, "model", "provider");
            assertTrue(prompt.contains(RuntimeArtifacts.directory(origin).toString()));
            RuntimeCheckpoint.save(origin, "恢复任务", "待查询原文", "index", List.of());
            assertTrue(RuntimeCheckpoint.previousHint(origin.withOriginMessageId(2L)).contains("checkpoint.json"));
        } finally {
            vip.mate.tool.guard.WorkspacePathGuard.setDefaultRoot(previous == null ? null : previous.toString());
        }
    }

    /** 跨会话及显式新输入生成独立目录，上一轮检查点可读但不会自动执行。 */
    @Test
    void artifactsAndCheckpointAreScopedToConversationAndInput(@TempDir Path root) throws Exception {
        var first = ChatOrigin.web("conv-a", "user", 1L, root.toString()).withOriginMessageId(1L);
        var second = first.withOriginMessageId(2L);
        var other = first.withConversationId("conv-b");
        assertNotEquals(RuntimeArtifacts.directory(first), RuntimeArtifacts.directory(second));
        assertNotEquals(RuntimeArtifacts.directory(first), RuntimeArtifacts.directory(other));
        var pending = List.of(new AssistantMessage.ToolCall("call-1", "function", "write_document", "{}"));
        RuntimeCheckpoint.save(first, "分析文件并生成报告", "当前待确认 call-1", "/receipt/index", pending);
        assertEquals("", RuntimeCheckpoint.previousHint(first));
        assertTrue(RuntimeCheckpoint.previousHint(second).contains("checkpoint.json"));
        assertEquals("", RuntimeCheckpoint.previousHint(other));
        Path oldSnapshot = RuntimeArtifacts.directory(first).resolve("checkpoint.json");
        RuntimeCheckpoint.save(second, "新输入目标", "准备读取上一轮", null,
                List.of(new AssistantMessage.ToolCall("read-old", "function", "read_file", "{}")));
        var oldJson = new ObjectMapper().readTree(Files.readString(oldSnapshot));
        assertEquals("分析文件并生成报告", oldJson.path("goal").asText());
        assertEquals("call-1", oldJson.path("unconfirmedCalls").get(0).path("toolCallId").asText());
        RuntimeCheckpoint.save(first, "分析文件并生成报告", "当前待确认 call-1", "/receipt/index", pending);
        var json = new ObjectMapper().readTree(Files.readString(RuntimeArtifacts.directory(first)
                .getParent().resolve("checkpoint.json")));
        assertEquals("call-1", json.path("unconfirmedCalls").get(0).path("toolCallId").asText());
        RuntimeCheckpoint.save(first, "分析文件并生成报告", "下一步展示报告", "/receipt/index", List.of());
        json = new ObjectMapper().readTree(Files.readString(RuntimeArtifacts.directory(first)
                .getParent().resolve("checkpoint.json")));
        assertTrue(json.path("unconfirmedCalls").isEmpty());
    }

    /** 超过 100KB 的目录分行完整保存，读取正文不会再次膨胀恢复目录。 */
    @Test
    void largeDirectoryKeepsAllReferencesWithoutReadResults(@TempDir Path root) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(root.toString());
        var storage = new ToolResultStorage(properties);
        var events = new ArrayList<GraphEventPublisher.GraphEvent>();
        var calls = new ArrayList<AssistantMessage.ToolCall>();
        for (int i = 0; i < 400; i++) {
            String id = "call-" + i;
            events.add(new GraphEventPublisher.GraphEvent(GraphEventPublisher.EVENT_TOOL_COMPLETE,
                    java.util.Map.of("toolCallId", id, "toolName", "analyze_file", "result", "证据".repeat(400),
                            "success", true), i));
            calls.add(new AssistantMessage.ToolCall(id, "function", "analyze_file", "{\"path\":\"" + "文档".repeat(80) + "\"}"));
        }
        events.add(GraphEventPublisher.toolComplete("read", "read_file", "嵌套原文".repeat(10000), true));
        var ledger = ActionExecutionLedger.fromEvents(events).preserveResults(events, calls, storage, "conv", root.toString())
                .preserveIndex(storage, "conv", root.toString());
        String index = Files.readString(Path.of(ledger.recoveryIndexPath()));
        assertTrue(index.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100_000);
        assertTrue(index.lines().allMatch(line -> line.length() < 2000));
        var reader = new vip.mate.tool.builtin.ReadFileTool(org.mockito.Mockito.mock(vip.mate.i18n.I18nService.class,
                invocation -> "msg".equals(invocation.getMethod().getName()) ? invocation.getArgument(0) : null));
        var rebuilt = new StringBuilder();
        Integer startLine = 1;
        int pages = 0;
        do {
            var page = new ObjectMapper().readTree(reader.read_file(ledger.recoveryIndexPath(), startLine,
                    10000, null, null));
            assertFalse(page.path("error").asBoolean());
            assertFalse(page.path("lineTruncated").asBoolean());
            for (String line : page.path("content").asText().split("\n")) {
                int tab = line.indexOf('\t');
                if (tab >= 0) rebuilt.append(line.substring(tab + 1)).append('\n');
            }
            pages++;
            assertTrue(pages < 100, "分页游标必须前进");
            if (!page.path("truncated").asBoolean()) break;
            int nextLine = page.path("nextStartLine").asInt();
            assertTrue(nextLine > startLine);
            startLine = nextLine;
        } while (true);
        assertTrue(pages > 1);
        assertEquals(new ObjectMapper().readTree(index), new ObjectMapper().readTree(rebuilt.toString()));
        var receipts = new ObjectMapper().readTree(index).path("receipts");
        assertEquals(400, receipts.size());
        assertEquals("call-399", receipts.get(0).path("toolCallId").asText());
        assertEquals("call-0", receipts.get(399).path("toolCallId").asText());
        assertFalse(index.contains("嵌套原文"));
        assertEquals("证据".repeat(400), Files.readString(Path.of(receipts.get(399).path("resultRef").asText())));
        assertTrue(ledger.renderAnswerFacts(Set.of(), "继续使用 call-0").contains("call-0"));
    }

    /** 同份可见正文不再复制进执行前缀，旧引用仍可精确恢复。 */
    @Test
    void visibleResultsAreNotDuplicatedInPrefix() {
        var ledger = ActionExecutionLedger.fromEvents(List.of(
                GraphEventPublisher.toolComplete("early", "query_data", "精确数据12345", true),
                GraphEventPublisher.toolComplete("doc", "readSkillFile", "规则正文", true)));
        String facts = ledger.renderAnswerFacts(Set.of("early"), "下一步基于 early 生成报告");
        assertFalse(facts.contains("精确数据12345"));
        assertTrue(facts.contains("alreadyReadMaterials"));
        assertTrue(facts.contains("early"));
    }
}
