package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

/**
 * 按问题分析会话上传附件或 MCP 返回的原图，向主模型提供可核实的视觉证据。
 *
 * <p>上传附件按文件名定位，省略名称时选取最近的上传图片。MCP 原图必须使用工具
 * 响应中的完整图片标识，只允许读取当前会话的媒体，不开放任意文件路径。
 * 图片与本轮问题一起发送给已配置的默认视觉模型，返回具体的画面证据供主模型使用。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAnalyzeTool {

    private final ConversationService conversationService;
    private final MultimodalRouter multimodalRouter;
    private final MediaCaptionService mediaCaptionService;

    /** 解析当前会话中 MCP 返回的图片引用。 */
    private vip.mate.tool.mcp.runtime.McpImageStore mcpImageStore;

    /**
     * 注入会话范围内的 MCP 图片解析服务。
     * @param store MCP 原图存储服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setMcpImageStore(vip.mate.tool.mcp.runtime.McpImageStore store) {
        this.mcpImageStore = store;
    }

    /**
     * 将指定会话图片与具体问题送入默认视觉模型，返回视觉识别证据。
     * @param question 需要结合原图回答的具体问题
     * @param image 上传附件名称，或 MCP 响应中返回的 mcp-image 图片标识
     * @param ctx 当前会话上下文
     * @return 图片分析结果；缺少图片、模型或分析失败时返回明确的说明
     */
    @Tool(description = "Analyze an image uploaded or returned by an MCP tool in this conversation, answering a specific "
            + "question about it. Use this whenever the user asks a follow-up about a previously sent image "
            + "(e.g. 'what does the error in that screenshot say', 'read the total on the receipt') and the "
            + "current model cannot see images natively. By default it analyzes the most recent image; pass "
            + "'image' to target a specific one by filename. For MCP images, always pass the exact mcp-image-* "
            + "reference returned by the tool. Returns the vision model's answer as text.")
    public String image_analyze(
            @ToolParam(description = "The specific question to answer about the image, in the user's own words. "
                    + "Be concrete — e.g. 'What is the error message?' rather than 'describe it'.") String question,
            @ToolParam(description = "MCP image reference (mcp-image-*.jpg/png/webp/gif), or optional filename of an uploaded image. Omit to use the most recent "
                    + "image in the conversation.", required = false) String image,
            @Nullable ToolContext ctx
    ) {
        if (question == null || question.isBlank()) {
            return "请提供要针对图片回答的具体问题。";
        }

        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId == null || conversationId.isBlank()) {
            return "无法确定当前会话，无法定位已上传的图片。";
        }

        MessageContentPart target = mcpImageStore == null ? null : mcpImageStore.find(image, ctx);
        if (target == null) target = findImagePart(conversationId, image);
        if (target == null) {
            return image == null || image.isBlank()
                    ? "本次会话中没有找到可分析的图片，请确认用户已上传图片。"
                    : "未找到名为「" + image + "」的图片，请检查文件名，或省略该参数以分析最近的图片。";
        }

        ModelConfigEntity visionModel = multimodalRouter.resolveVisionSidecar();
        if (visionModel == null) {
            return "尚未配置视觉模型，无法分析图片。请在「设置 → 模型」中将一个具备视觉能力的模型设为默认视觉模型。";
        }

        // Locale null → caption service answers in the question's own language.
        MediaCaptionService.CaptionResult result =
                mediaCaptionService.caption(visionModel, target, null, question);
        if (result.isFailure()) {
            log.warn("[image_analyze] caption failed for {} via {}/{}: {}",
                    target.getFileName(), visionModel.getProvider(), visionModel.getModelName(),
                    result.failure() == null ? "unknown" : result.failure().getMessage());
            return "视觉模型未能解析该图片（" + safeName(target) + "），请稍后重试或检查视觉模型配置。";
        }
        return result.description();
    }

    /**
     * Resolve the target image part from the conversation. With a reference,
     * matches by filename basename / path / mediaId suffix, scanning newest-first.
     * Without one, returns the most recent image part.
     */
    private MessageContentPart findImagePart(String conversationId, String reference) {
        List<MessageEntity> messages;
        try {
            messages = conversationService.listMessages(conversationId);
        } catch (Exception e) {
            log.warn("[image_analyze] failed to load messages for {}: {}", conversationId, e.getMessage());
            return null;
        }
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String ref = reference == null ? null : reference.trim();
        boolean wildcard = ref == null || ref.isBlank()
                || ref.equalsIgnoreCase("latest") || ref.equalsIgnoreCase("last");
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<MessageContentPart> parts = conversationService.parseMessageParts(messages.get(i));
            for (int j = parts.size() - 1; j >= 0; j--) {
                MessageContentPart part = parts.get(j);
                if (!isImage(part)) continue;
                if (wildcard || matchesReference(part, ref)) {
                    return part;
                }
            }
        }
        return null;
    }

    private boolean isImage(MessageContentPart part) {
        if (part == null) return false;
        String type = part.getType();
        String contentType = part.getContentType();
        boolean image = "image".equals(type)
                || ("file".equals(type) && contentType != null && contentType.startsWith("image/"));
        return image && !(contentType != null && contentType.contains("svg"));
    }

    private boolean matchesReference(MessageContentPart part, String ref) {
        if (ref == null) return false;
        String basename = ref.contains("/") || ref.contains("\\")
                ? ref.substring(Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\')) + 1)
                : ref;
        return endsWithIgnoreCase(part.getFileName(), basename)
                || endsWithIgnoreCase(part.getPath(), ref)
                || endsWithIgnoreCase(part.getMediaId(), ref);
    }

    private boolean endsWithIgnoreCase(String value, String suffix) {
        if (value == null || suffix == null || suffix.isBlank()) return false;
        return value.toLowerCase().endsWith(suffix.toLowerCase());
    }

    private String safeName(MessageContentPart part) {
        String name = part.getFileName();
        return name == null || name.isBlank() ? "image" : name;
    }
}
