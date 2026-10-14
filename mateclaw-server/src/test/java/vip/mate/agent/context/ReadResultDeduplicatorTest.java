package vip.mate.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import static org.junit.jupiter.api.Assertions.*;

/** 验证同请求正文合并的来源、内容变化、调用关联与预算后引用边界。 */
class ReadResultDeduplicatorTest {
    /** 范围参数不同但实际正文相同时，仅合并正文，调用标识和响应状态仍保留。 */
    @Test
    void sameContentKeepsLatestBodyAndOriginalWrapper() throws Exception {
        String content = "1\t实际证据".repeat(500);
        var first = read("old", "source.txt", content, 1, 50, true);
        var last = read("latest", "source.txt", content, 1, 100, false);
        List<Message> input = List.of(first, last);
        var projected = ReadResultDeduplicator.project(input);
        var old = (ToolResponseMessage) projected.get(0);
        var node = new ObjectMapper().readTree(old.getResponses().getFirst().responseData());
        assertTrue(node.path("content").asText().contains("latest"));
        assertEquals(50, node.path("endLine").asInt());
        assertTrue(node.path("truncated").asBoolean());
        assertEquals("old", old.getResponses().getFirst().id());
        assertEquals(first.getMetadata(), old.getMetadata());
        assertSame(last, projected.get(1));
        assertTrue(first.getResponses().getFirst().responseData().contains("实际证据"));
        assertTrue(TokenEstimator.estimateTokens(projected) < TokenEstimator.estimateTokens(input));
    }

    /** 同路径内容变化、同内容不同来源或起点均不合并，不能假定文件版本。 */
    @Test
    void changedContentAndDifferentSourcesRemainVisible() throws Exception {
        String content = "证据".repeat(500);
        List<Message> input = List.of(read("a", "one.txt", content, 1, 50, false),
                read("b", "one.txt", content + "新版本", 1, 50, false),
                read("c", "other.txt", content, 1, 50, false),
                read("d", "one.txt", content, 2, 50, false));
        assertSame(input, ReadResultDeduplicator.project(input));
    }

    /** 预算裁掉最新副本后，用原历史重新装配时仍保留剩余完整正文，不形成悬空引用。 */
    @Test
    void recomputingAfterTailCutKeepsRemainingBody() throws Exception {
        String content = "原始正文".repeat(500);
        List<Message> original = List.of(read("old", "source.txt", content, 1, 50, false),
                read("new", "source.txt", content, 1, 100, false));
        ReadResultDeduplicator.project(original);
        List<Message> cut = original.subList(0, 1);
        assertSame(cut, ReadResultDeduplicator.project(cut));
        assertTrue(((ToolResponseMessage) cut.getFirst()).getResponses().getFirst().responseData().contains(content));
    }

    /** 业务查询即使正文完全相同也不参与读取正文合并。 */
    @Test
    void businessQueriesAndMalformedReadsStayUnchanged() {
        String content = "返回结果".repeat(500);
        List<Message> input = List.of(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("q1", "query_data", content),
                new ToolResponseMessage.ToolResponse("q2", "query_data", content),
                new ToolResponseMessage.ToolResponse("r1", "read_file", "实际错误字符串"))).build());
        assertSame(input, ReadResultDeduplicator.project(input));
    }

    /** 构造原生读取协议响应，并保留原消息元数据。 */
    private ToolResponseMessage read(String id, String path, String content, int line, int end, boolean truncated) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("filePath", path, "startLine", line,
                "startColumn", 1, "endLine", end, "content", content, "truncated", truncated));
        return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id, "read_file", body)))
                .metadata(Map.of("origin", id)).build();
    }
}
