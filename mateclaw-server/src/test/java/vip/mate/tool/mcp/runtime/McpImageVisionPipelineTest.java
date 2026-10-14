package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.support.RetryTemplate;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolResultProperties;
import vip.mate.agent.graph.executor.ToolResultStorage;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.MediaCaptionService;
import vip.mate.llm.routing.MultimodalRouter;
import vip.mate.tool.builtin.ImageAnalyzeTool;
import vip.mate.workspace.conversation.ConversationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证 MCP 图片经过真实回调与图片分析工具后，以媒体而非 Base64 文本进入视觉模型。 */
class McpImageVisionPipelineTest {

    /** 测试媒体根目录，避免读取实际业务图片。 */
    @TempDir
    Path temporary;

    /** 一像素 PNG 原图，用于验证传递过程中字节未被截断或改写。 */
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jA1cAAAAASUVORK5CYII=";

    /**
     * 创建使用测试目录的工具存储。
     * @return 与正式实现一致的工具结果存储
     */
    private ToolResultStorage storage() {
        ToolResultProperties properties = new ToolResultProperties();
        properties.setStorageBaseDir(temporary.toString());
        return new ToolResultStorage(properties);
    }

    /**
     * 建立真实会话上下文。
     * @param conversation 会话标识
     * @return 供工具回调及图片分析共用的上下文
     */
    private ToolContext context(String conversation) {
        return ChatOrigin.web(conversation, "tester", 1L, null).toToolContext();
    }

    /**
     * 提取图片分析指引中的标识，并要求响应不携带原始 Base64。
     * @param output 模型收到的工具响应
     * @return 可交给图片分析工具的会话内标识
     */
    private String reference(String output) {
        assertFalse(output.contains(PNG));
        var matcher = Pattern.compile("mcp-image-[0-9a-f-]+\\.png").matcher(output);
        assertTrue(matcher.find(), output);
        return matcher.group();
    }

    /** 常规 MCP 回调保留说明文字和原图，以会话标识替换图片 Base64。 */
    @Test
    void normalCallbackPreservesImageAndText() throws Exception {
        McpImageStore store = new McpImageStore(storage());
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool = McpSchema.Tool.builder().name("get-image").description("原图")
                .inputSchema(new JacksonMcpJsonMapper(new ObjectMapper()), "{\"type\":\"object\"}").build();
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(
                new McpSchema.TextContent("mediaUid=report-1"),
                new McpSchema.ImageContent(null, PNG, "image/png")), false));
        String output = new ApplicationMcpToolCallback(client, tool, store).call("{}", context("case-a"));
        assertTrue(output.contains("mediaUid=report-1"));
        var part = store.find(reference(output), context("case-a"));
        assertArrayEquals(Base64.getDecoder().decode(PNG), Files.readAllBytes(Path.of(part.getPath())));
        assertEquals("image/png", part.getContentType());
        verify(client, times(1)).callTool(any());
    }

    /** 正式包装链自动返回视觉证据，后续图片追问仍可读取同一原图。 */
    @Test
    @SuppressWarnings("unchecked")
    void capturedCallbackFeedsRealVisionPrompt() throws Exception {
        McpImageStore store = new McpImageStore(storage());
        McpSyncClient client = mock(McpSyncClient.class);
        McpClientManager manager = new McpClientManager(mock(ApplicationEventPublisher.class),
                new McpIdentityForwardService(new McpIdentityForwardProperties()), null, new ObjectMapper());
        manager.setImageStore(store);
        var field = McpClientManager.class.getDeclaredField("clients");
        field.setAccessible(true);
        ((Map<Long, McpSyncClient>) field.get(manager)).put(1L, client);
        McpSchema.Tool remote = McpSchema.Tool.builder().name("get-image").description("原图")
                .inputSchema(new JacksonMcpJsonMapper(new ObjectMapper()), "{\"type\":\"object\"}").build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(remote), null));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(
                new McpSchema.ImageContent(null, PNG, "image/png")), false));
        ModelConfigEntity model = mock(ModelConfigEntity.class);
        MultimodalRouter router = mock(MultimodalRouter.class);
        when(router.resolveVisionSidecar()).thenReturn(model);
        ProviderChatModelFactory factory = mock(ProviderChatModelFactory.class);
        ChatModel vision = mock(ChatModel.class);
        when(vision.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(factory.buildFor(any(), any())).thenReturn(vision);
        when(vision.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("可见一个像素")))));
        MediaCaptionService captions = new MediaCaptionService(factory, new RetryTemplate());
        store.setVisionServices(router, captions);
        ToolContext context = new McpToolResultCapture().attach(context("case-a"));
        String output = manager.getAllToolCallbacks().getFirst().call("{}", context);
        assertTrue(output.contains("[视觉识别结果] 可见一个像素"));
        ImageAnalyzeTool analyze = new ImageAnalyzeTool(mock(ConversationService.class), router,
                captions);
        analyze.setMcpImageStore(store);
        assertEquals("可见一个像素", analyze.image_analyze("检查可见图像细节", reference(output), context));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(vision, times(2)).call(prompt.capture());
        UserMessage automatic = prompt.getAllValues().getFirst().getUserMessage();
        assertTrue(automatic.getText().contains("工具返回的原图"));
        assertArrayEquals(Base64.getDecoder().decode(PNG), (byte[]) automatic.getMedia().getFirst().getData());
        UserMessage user = prompt.getAllValues().getLast().getUserMessage();
        assertTrue(user.getText().contains("检查可见图像细节"));
        assertFalse(user.getText().contains(PNG));
        assertEquals(1, user.getMedia().size());
        assertEquals("image/png", user.getMedia().getFirst().getMimeType().toString());
        assertArrayEquals(Base64.getDecoder().decode(PNG), (byte[]) user.getMedia().getFirst().getData());
        verify(client, times(1)).callTool(any());
    }

    /** 会话隔离、路径拒绝和重启后原图读取均通过持久化标识生效。 */
    @Test
    void referencesStayWithinConversationAndSurviveRestart() {
        McpImageStore store = new McpImageStore(storage());
        String reference = reference(store.describe(new McpSchema.ImageContent(null, PNG, "image/png"), context("case-a")));
        assertNotNull(new McpImageStore(storage()).find(reference, context("case-a")));
        assertNull(store.find(reference, context("case-b")));
        assertNull(store.find("../case-a/" + reference, context("case-b")));
    }

    /** 无会话或 Base64 损坏时明确报告图片不可用，不将二进制当作文字返回。 */
    @Test
    void invalidImageOrMissingContextReportsEvidenceFailure() {
        McpImageStore store = new McpImageStore(storage());
        assertTrue(store.describe(new McpSchema.ImageContent(null, "broken!", "image/png"), context("case-a"))
                .contains("图片不可用"));
        assertTrue(store.describe(new McpSchema.ImageContent(null, PNG, "image/png"), null)
                .contains("图片不可用"));
    }

    /** 删除会话时，原图沿用工具结果清理机制，不留下独立的媒体生命周期。 */
    @Test
    void conversationPurgeRemovesStoredImage() {
        ToolResultStorage storage = storage();
        McpImageStore store = new McpImageStore(storage);
        String reference = reference(store.describe(new McpSchema.ImageContent(null, PNG, "image/png"), context("case-a")));
        assertEquals(1, storage.purgeConversation("case-a"));
        assertNull(store.find(reference, context("case-a")));
    }

    /** 缺少默认视觉模型时保留原图引用，并明确区分保存成功与识图失败。 */
    @Test
    void missingVisionModelDoesNotPretendImageWasAnalyzed() {
        McpImageStore store = new McpImageStore(storage());
        MultimodalRouter router = mock(MultimodalRouter.class);
        MediaCaptionService captions = mock(MediaCaptionService.class);
        store.setVisionServices(router, captions);
        String output = store.describe(new McpSchema.ImageContent(null, PNG, "image/png"), context("case-a"));
        assertNotNull(store.find(reference(output), context("case-a")));
        assertTrue(output.contains("[视觉识别失败]"));
        assertFalse(output.contains("[视觉识别结果]"));
        verifyNoInteractions(captions);
    }

    /** 直接返回工具的图片不能被自动送到外部视觉模型，本地隔离标记不转发给 MCP。 */
    @Test
    void returnDirectSkipsAutomaticVision() {
        McpImageStore store = new McpImageStore(storage());
        MultimodalRouter router = mock(MultimodalRouter.class);
        MediaCaptionService captions = mock(MediaCaptionService.class);
        store.setVisionServices(router, captions);
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool tool = McpSchema.Tool.builder().name("get-image").description("原图")
                .inputSchema(new JacksonMcpJsonMapper(new ObjectMapper()), "{\"type\":\"object\"}").build();
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(
                new McpSchema.ImageContent(null, PNG, "image/png")), false));
        var callback = new ReturnDirectMcpToolCallback(new ApplicationMcpToolCallback(client, tool, store));
        String output = callback.call("{}", context("case-a"));
        assertNotNull(store.find(reference(output), context("case-a")));
        assertFalse(output.contains("[视觉识别结果]"));
        verifyNoInteractions(router, captions);
        verify(client).callTool(argThat(request -> !request.meta().containsKey(McpImageStore.SKIP_VISION_CONTEXT_KEY)));
    }
}
