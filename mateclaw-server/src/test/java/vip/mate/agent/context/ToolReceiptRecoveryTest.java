package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.config.ConversationWindowProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证领取回执经过多轮取证和压缩后仍可读取，避免通过重复领取找回结果。 */
class ToolReceiptRecoveryTest {

    /** 短领取回执在超过最近三轮后仍保留完整明细。 */
    @Test
    void shortClaimSurvivesThirtyEvidenceRounds() {
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        String claim = "{\"claimed\":[{\"detailId\":7118},{\"detailId\":7119}]}";
        List<Message> messages = new ArrayList<>();
        messages.add(response("claim-1", "claim_case", claim));
        for (int i = 0; i < 30; i++) {
            messages.add(response("read-" + i, "read_evidence", "evidence-" + i));
            messages = manager.compactAgedToolResponses(messages, 3);
        }
        assertEquals(claim, ((ToolResponseMessage) messages.getFirst()).getResponses().getFirst().responseData());
    }

    /** 没有存储时保留长回执，避免产生无法找回的清理占位符。 */
    @Test
    void unavailableStorageKeepsOriginalResult() {
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        String original = "claim-result-".repeat(200);
        var messages = List.<Message>of(response("claim-1", "claim_case", original),
                response("latest", "read_evidence", "latest"));
        assertEquals(original, ((ToolResponseMessage) manager.compactAgedToolResponses(messages, 1)
                .getFirst()).getResponses().getFirst().responseData());
    }

    /** 小于原大结果门槛的长回执也必须先落盘，恢复时读取同一份原文。 */
    @Test
    void agedResultBelowOversizeThresholdHasRecoveryReference(@TempDir Path directory) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(directory.toString());
        var storage = new ToolResultStorage(properties);
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        manager.setToolResultStorage(storage);
        String original = "领取明细及必要证据".repeat(200);
        var messages = List.<Message>of(response("claim-1", "claim_case", original),
                response("latest", "read_evidence", "latest"));
        var compacted = manager.compactAgedToolResponses(messages, 1, "conv-recovery", directory.toString());
        String reference = ((ToolResponseMessage) compacted.getFirst()).getResponses().getFirst().responseData();
        assertTrue(reference.startsWith(ToolResultStorage.SPILL_MARKER_PREFIX));
        assertEquals(original, Files.readString(Path.of(ToolResultStorage.spillPath(reference))));
        assertFalse(reference.contains("can be called again"));
        assertEquals(reference, ((ToolResponseMessage) manager.compactAgedToolResponses(compacted, 1,
                "conv-recovery", directory.toString()).getFirst()).getResponses().getFirst().responseData());
    }

    /** 构造具有真实调用关联标识的工具响应。 */
    private static ToolResponseMessage response(String id, String tool, String result) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(id, tool, result))).build();
    }

    /** 所有摘要前处理阶段均保留短领取回执，不能因其较旧而清理。 */
    @Test
    void shortClaimSurvivesAllSummaryPhases() {
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        String claim = "{\"detailId\":7118,\"requestId\":\"original\"}";
        List<Message> messages = new ArrayList<>(List.of(response("claim", "claim_case", claim)));
        manager.softTrimToolResults(messages);
        manager.hardClearToolResults(messages);
        manager.prePruneForSummary(messages);
        assertEquals(claim, ((ToolResponseMessage) messages.getFirst()).getResponses().getFirst().responseData());
    }

    /** 在旧去重门槛与短回执门槛之间的重复结果也保持完整调用事实。 */
    @Test
    void duplicateShortReceiptIsNotOmitted() {
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        String body = "x".repeat(512);
        var messages = List.<Message>of(response("old", "claim_case", body), response("new", "claim_case", body));
        assertEquals(body, ((ToolResponseMessage) manager.pruneOldToolResultsForModelInput(messages)
                .getFirst()).getResponses().getFirst().responseData());
    }

    /** 重复压缩只能引用已有完整原文，索引可以更新且会话之间互不覆盖。 */
    @Test
    void immutableReceiptAndMutableIndexAreConversationScoped(@TempDir Path directory) throws Exception {
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(directory.toString());
        var storage = new ToolResultStorage(properties);
        String path = storage.preserveForRecovery("完整原文", "claim_case", "same-id", "one", null);
        assertEquals(path, storage.preserveForRecovery("裁剪正文", "claim_case", "same-id", "one", null));
        assertEquals("完整原文", Files.readString(Path.of(path)));
        String peer = storage.preserveForRecovery("另一个会话", "claim_case", "same-id", "two", null);
        assertNotEquals(path, peer);
        assertEquals("另一个会话", Files.readString(Path.of(peer)));
        String index = storage.preserveRecoveryIndex("first", "same-id", "one", null);
        assertEquals(index, storage.preserveRecoveryIndex("updated", "same-id", "one", null));
        assertEquals("updated", Files.readString(Path.of(index)));
    }

    /** 实际存储故障时保留原文，不能生成虚假的恢复入口。 */
    @Test
    void failedWriteKeepsOriginal(@TempDir Path directory) throws Exception {
        Path blocked = Files.writeString(directory.resolve("file-instead-of-directory"), "occupied");
        var properties = new ToolResultProperties();
        properties.setStorageBaseDir(blocked.toString());
        var storage = new ToolResultStorage(properties);
        String original = "原始领取回执".repeat(200);
        assertEquals(original, storage.compactForRecovery(original, "claim_case", "call", "conv", null));
        assertNull(storage.preserveRecoveryIndex("{}", "call", "conv", null));
        var manager = new ConversationWindowManager(new ConversationWindowProperties(), null, null);
        manager.setToolResultStorage(storage);
        List<Message> messages = new ArrayList<>(List.of(response("call", "claim_case", original)));
        manager.spillEvictToolResults(messages, "conv", null);
        manager.softTrimToolResults(messages);
        manager.hardClearToolResults(messages);
        manager.prePruneForSummary(messages);
        assertEquals(original, ((ToolResponseMessage) messages.getFirst()).getResponses().getFirst().responseData());
    }
}
