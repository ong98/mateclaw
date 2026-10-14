package vip.mate.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** 在最终模型请求内合并同来源、同读取起点及完全相同正文，保留最近完整副本和全部调用关联。 */
public final class ReadResultDeduplicator {
    /** 读取结果采用现有 read_file JSON 协议，原文件及原始响应不在此改写。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 短正文直接保留，避免引用标记反而增加输入。 */
    private static final int MIN_CONTENT_CHARS = 512;

    /** 禁止实例化无状态的请求投影器。 */
    private ReadResultDeduplicator() { }

    /**
     * 相同读取正文的精确比较键，不按路径假定文件版本相同。
     * @param path 实际响应中的来源路径
     * @param line 实际读取起始行
     * @param column 实际读取起始列
     * @param content 完整的已返回正文，变化后的内容使用不同键
     */
    private record Body(String path, int line, int column, String content) { }

    /**
     * 在历史预算选择之后合并重复正文，引用目标保证仍在本请求内。
     * @param messages 已选定的最终模型历史，不修改调用方集合或消息
     * @return 正文去重后的历史；未发现精确重复时返回原列表
     */
    public static List<Message> project(List<Message> messages) {
        var seen = new HashMap<Body, String>();
        var result = new ArrayList<>(messages);
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof ToolResponseMessage message)) continue;
            var responses = new ArrayList<>(message.getResponses());
            boolean messageChanged = false;
            for (int j = responses.size() - 1; j >= 0; j--) {
                var response = responses.get(j);
                if (!"read_file".equals(ToolResponseIdentity.name(message, response)) || response.responseData() == null) continue;
                try {
                    var parsed = JSON.readTree(response.responseData());
                    if (!(parsed instanceof ObjectNode node) || !node.path("filePath").isTextual()
                            || !node.path("content").isTextual() || node.path("error").asBoolean()) continue;
                    String content = node.path("content").asText();
                    if (content.length() <= MIN_CONTENT_CHARS) continue;
                    var key = new Body(node.path("filePath").asText(), node.path("startLine").asInt(1),
                            node.path("startColumn").asInt(1), content);
                    String canonical = seen.putIfAbsent(key, response.id());
                    if (canonical == null) continue;
                    node.put("content", "[重复读取正文已合并；同来源及同起点的完整正文位于本请求 read_file 调用 "
                            + canonical + " 的回应中。本次读取范围及截断状态保持原值。]");
                    responses.set(j, new ToolResponseMessage.ToolResponse(response.id(), response.name(), node.toString()));
                    messageChanged = true;
                } catch (JsonProcessingException invalid) {
                    // 错误字符串或非 JSON 响应保持原样，不能用解析失败猜测内容或执行状态。
                }
            }
            if (messageChanged) {
                result.set(i, ToolResponseMessage.builder().responses(responses).metadata(message.getMetadata()).build());
                changed = true;
            }
        }
        return changed ? result : messages;
    }
}
