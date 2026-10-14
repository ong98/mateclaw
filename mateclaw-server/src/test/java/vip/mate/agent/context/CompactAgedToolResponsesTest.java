package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.graph.executor.ToolResultStorage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证按消息年龄处理时保留短回执、调用关联和只读恢复路径。
 */
class CompactAgedToolResponsesTest {

    private static final ConversationWindowManager MANAGER = new ConversationWindowManager(
            null, null, null);

    private static ToolResponseMessage toolResp(String id, String name, String body) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, body)))
                .build();
    }

    private static String spillBody(String tool, String path) {
        return ToolResultStorage.SPILL_MARKER_PREFIX + " tool=" + tool + " full_chars=12345 path="
                + path
                + "\n[Preview — first 800 of 12345 chars. The preview is INCOMPLETE: use read_file]\n"
                + "<preview content>\n…[truncated]";
    }

    @Test
    @DisplayName("keepRecentN=0 or negative is a no-op (returns same list reference).")
    void zeroKeepIsNoop() {
        List<Message> in = List.of(toolResp("t1", "search", "a body".repeat(50)));
        assertSame(in, MANAGER.compactAgedToolResponses(in, 0));
        assertSame(in, MANAGER.compactAgedToolResponses(in, -1));
    }

    @Test
    /** 短回执即使早于最近展示范围也完整保留。 */
    @DisplayName("Short receipts remain verbatim beyond the latest K responses.")
    void keepsLatestKVerbatim() {
        String body = "abcdef".repeat(50);  // 300 chars — guaranteed larger than placeholder
        List<Message> in = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            in.add(toolResp("call-" + i, "search", "result-" + i + " " + body));
        }
        List<Message> out = MANAGER.compactAgedToolResponses(in, 2);
        // out[3] and out[4] are the two newest — verbatim.
        assertTrue(((ToolResponseMessage) out.get(3)).getResponses().get(0).responseData().contains("result-3"));
        assertTrue(((ToolResponseMessage) out.get(4)).getResponses().get(0).responseData().contains("result-4"));
        // 较早的短回执仍可直接读取。
        for (int i = 0; i <= 2; i++) {
            String compactedBody = ((ToolResponseMessage) out.get(i)).getResponses().get(0).responseData();
            assertEquals("result-" + i + " " + body, compactedBody);
        }
    }

    @Test
    /** 保真处理保持原调用标识和工具名称，缺少存储时保留正文。 */
    @DisplayName("Tool name and toolCallId survive the rewrite so the assistant/tool pair stays valid.")
    void preservesIdAndName() {
        String big = "a".repeat(500);
        List<Message> in = List.of(
                toolResp("call-old", "search", big),
                toolResp("call-mid", "search", big),
                toolResp("call-new", "search", big));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        ToolResponseMessage.ToolResponse old = ((ToolResponseMessage) out.get(0)).getResponses().get(0);
        assertEquals("call-old", old.id());
        assertEquals("search", old.name());
        assertEquals(big, old.responseData());
        // Latest stays verbatim.
        assertEquals(big, ((ToolResponseMessage) out.get(2)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("Spill-marker bodies keep their on-disk path inside the placeholder for read_file recovery.")
    void spillPathPreserved() {
        String body = spillBody("browser_use", "/tmp/mateclaw/tool-results/conv-1/tool_abc.txt");
        List<Message> in = List.of(
                toolResp("call-old", "browser_use", body),
                toolResp("call-new", "search", "a".repeat(500)));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        String compacted = ((ToolResponseMessage) out.get(0)).getResponses().get(0).responseData();
        assertTrue(compacted.contains("/tmp/mateclaw/tool-results/conv-1/tool_abc.txt"),
                "spill path should be preserved in placeholder: " + compacted);
        assertTrue(compacted.contains("read_file"), compacted);
    }

    @Test
    /** 豁免结果和无存储的普通回执均不被丢弃。 */
    @DisplayName("Exempt tools (delegateToAgent / delegateParallel) skip compaction entirely.")
    void exemptToolsSkipped() {
        String big = "x".repeat(500);
        List<Message> in = List.of(
                toolResp("d-old", "delegateToAgent", big),
                toolResp("s-old", "search", big),
                toolResp("s-new", "search", big));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        assertEquals(big, ((ToolResponseMessage) out.get(0)).getResponses().get(0).responseData());  // exempt
        assertEquals(big, ((ToolResponseMessage) out.get(1)).getResponses().get(0).responseData());
        assertEquals(big, ((ToolResponseMessage) out.get(2)).getResponses().get(0).responseData());  // newest kept
    }

    @Test
    /** 极短回执直接保留，避免恢复标记增加模型正文。 */
    @DisplayName("A body shorter than the placeholder itself is kept verbatim — no negative savings.")
    void tinyBodyNotInflated() {
        List<Message> in = List.of(
                toolResp("old", "ping", "ok"),
                toolResp("new", "ping", "ok"));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        assertEquals("ok", ((ToolResponseMessage) out.get(0)).getResponses().get(0).responseData());
    }

    @Test
    @DisplayName("Non-tool messages (user / assistant) are passed through untouched.")
    void nonToolMessagesUntouched() {
        Message user = new UserMessage("hello");
        Message assistant = new AssistantMessage("hi");
        List<Message> in = List.of(user, assistant,
                toolResp("old", "search", "x".repeat(500)),
                toolResp("new", "search", "y".repeat(500)));
        List<Message> out = MANAGER.compactAgedToolResponses(in, 1);
        assertSame(user, out.get(0));
        assertSame(assistant, out.get(1));
    }

}
