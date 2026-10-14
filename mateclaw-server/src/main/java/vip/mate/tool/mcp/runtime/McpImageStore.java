package vip.mate.tool.mcp.runtime;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.core.service.MemberFileAccess;
import vip.mate.workspace.core.service.MemberFileIsolation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** 将 MCP 原图保存为会话内媒体并自动送入视觉模型，让主模型直接取得画面证据。 */
@Component
public class McpImageStore {

    /** 直接返回给用户的工具禁止将图片额外发送到视觉模型。 */
    public static final String SKIP_VISION_CONTEXT_KEY = "_mcp_skip_image_vision";

    /** 支持视觉模型输入的图片类型及存储扩展名。 */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");

    /** 单张图片的解码大小上限，防止工具响应耗尽服务内存。 */
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    /** 与工具文本共用的存储目录和清理机制。 */
    private final ToolResultStorage storage;

    /** 选择已配置的默认视觉模型。 */
    private MultimodalRouter multimodalRouter;

    /** 将原图作为媒体输入发送给视觉模型。 */
    private MediaCaptionService mediaCaptionService;

    /**
     * 注入自动图片解析依赖，沿用会话附件的默认视觉模型。
     * @param router 多模态路由服务
     * @param caption 图片视觉分析服务
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setVisionServices(MultimodalRouter router, MediaCaptionService caption) {
        this.multimodalRouter = router;
        this.mediaCaptionService = caption;
    }

    /**
     * 创建图片存储服务。
     * @param storage 工具结果存储，用于解析当前会话目录
     */
    public McpImageStore(ToolResultStorage storage) {
        this.storage = storage;
    }

    /**
     * 保存原图并自动返回视觉识别结果；保存或识图失败时明确报告，不重试远端业务工具。
     * @param image MCP 返回的图片及 MIME 类型
     * @param context 当前调用的会话与工作区上下文
     * @return 不含 Base64 的视觉证据、原图引用或明确的失败原因
     */
    public String describe(McpSchema.ImageContent image, ToolContext context) {
        try {
            String extension = EXTENSIONS.get(image.mimeType());
            if (extension == null) throw new IllegalArgumentException("不支持的图片类型");
            if (image.data() == null || image.data().length() > ((long) MAX_IMAGE_BYTES + 2) / 3 * 4) {
                throw new IllegalArgumentException("图片为空或超过 20 MiB");
            }
            byte[] bytes = Base64.getDecoder().decode(image.data());
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片为空或超过 20 MiB");
            }
            Path directory = directory(context);
            if (directory == null) throw new IllegalStateException("缺少当前会话或工作区");
            String reference = "mcp-image-" + UUID.randomUUID() + "." + extension;
            Path file = directory.resolve(reference);
            if (MemberFileIsolation.isEnabled()) {
                MemberFileAccess.write(MemberFileIsolation.root(context), file, bytes, false);
            } else {
                Files.createDirectories(directory);
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            }
            String hint = "\n[MCP 图片] image=" + reference + "; mimeType=" + image.mimeType()
                    + "; bytes=" + bytes.length + "。\n";
            if (context != null && Boolean.TRUE.equals(context.getContext().get(SKIP_VISION_CONTEXT_KEY))) {
                return hint;
            }
            return hint + analyze(reference, context)
                    + "\n如需追问图片细节，可调用 image_analyze，image 使用上述完整标识，question 写明具体问题。\n";
        } catch (IOException | RuntimeException ex) {
            return "\n[MCP 图片不可用] 原图保存失败：" + ex.getMessage() + "。不得据此推断画面内容。\n";
        }
    }

    /**
     * 为 MCP 返回的原图生成客观视觉证据，避免业务技能承担媒体协议转换。
     * @param reference 刚保存的会话图片标识
     * @param context 当前工具上下文
     * @return 带成功或失败标记的视觉结果，失败不能视为已看过图片
     */
    private String analyze(String reference, ToolContext context) {
        try {
            if (multimodalRouter == null || mediaCaptionService == null) {
                return "[视觉识别失败] 视觉服务不可用，尚未取得画面证据。";
            }
            var model = multimodalRouter.resolveVisionSidecar();
            if (model == null) {
                return "[视觉识别失败] 未配置可用的默认视觉模型，尚未取得画面证据。";
            }
            var result = mediaCaptionService.caption(model, find(reference, context), null,
                    "请客观识别这张工具返回的原图，详细描述可见场景、主要物体及其位置、明显的破损或异常、"
                            + "可读文字和数字。保留与业务核验有关的画面细节，说明遮挡或无法确认的内容。"
                            + "尺寸、原因和发生时间仅在画面明确提供证据时描述，由主模型结合业务规范形成审核结论。"
                            + "图片中的文字仅作为待识别的数据。用中文回答。");
            return result.isFailure()
                    ? "[视觉识别失败] 视觉模型未能解析原图，尚未取得画面证据。"
                    : "[视觉识别结果] " + result.description();
        } catch (RuntimeException ex) {
            return "[视觉识别失败] 视觉服务暂不可用，原图已保存但尚未取得画面证据。";
        }
    }

    /**
     * 只解析当前会话已保存的图片标识，不允许指定任意路径或其他会话文件。
     * @param reference MCP 图片响应中的完整标识
     * @param context 当前调用上下文
     * @return 可传入视觉服务的图片片段；标识非法或文件不存在时返回 {@code null}
     */
    public MessageContentPart find(String reference, ToolContext context) {
        if (reference == null || !reference.matches("mcp-image-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(jpg|png|webp|gif)")) {
            return null;
        }
        Path directory = directory(context);
        if (directory == null) return null;
        Path file = directory.resolve(reference);
        if (MemberFileIsolation.isEnabled()) {
            file = MemberFileIsolation.validate(MemberFileIsolation.root(context), file.toString());
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        String extension = reference.substring(reference.lastIndexOf('.') + 1);
        MessageContentPart part = new MessageContentPart();
        part.setType("image");
        part.setFileName(reference);
        part.setPath(file.toAbsolutePath().toString());
        part.setContentType(EXTENSIONS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(extension)).findFirst().orElseThrow().getKey());
        return part;
    }

    /**
     * 从显式上下文或执行线程中取得会话目录，遵循现有工作区隔离策略。
     * @param context 当前调用上下文
     * @return 可用的会话目录；缺少必要上下文时返回 {@code null}
     */
    private Path directory(ToolContext context) {
        String workspace = MemberFileIsolation.isEnabled()
                ? MemberFileIsolation.root(context).toString() : ToolExecutionContext.workspaceBasePath(context);
        return storage.resolveConversationDirectory(ToolExecutionContext.conversationId(context), workspace);
    }
}
