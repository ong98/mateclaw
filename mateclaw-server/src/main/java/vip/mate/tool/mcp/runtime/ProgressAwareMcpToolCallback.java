package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * 为 MCP 调用注入进度令牌，捕获结构化结果，并将原图保存为会话内视觉引用。
 *
 * <p>When {@code MCP_PROGRESS_TOKEN} is present in {@link ToolContext}, this wrapper
 * calls {@link McpSyncClient#callTool(McpSchema.CallToolRequest)} directly with the
 * progressToken injected. A per-call result capture also selects this path so
 * structured content can reach the UI without a model rewriting it. With neither
 * context value present, it delegates to the original callback.
 */
@Slf4j
public final class ProgressAwareMcpToolCallback implements ToolCallback {

    /** Key in ToolContext where the progressToken is stored. */
    public static final String MCP_PROGRESS_TOKEN_KEY = "_mcp_progress_token";

    private final ToolCallback delegate;
    private final McpSyncClient mcpClient;
    private final String rawToolName;
    private final ObjectMapper objectMapper;

    /** 与常规调用共用的图片存储，避免进度调用将图片串成 Base64 文本。 */
    private McpImageStore imageStore;

    /**
     * 配置图片处理服务。
     * @param imageStore 当前会话图片存储服务
     */
    public void setImageStore(McpImageStore imageStore) {
        this.imageStore = imageStore;
    }

    public ProgressAwareMcpToolCallback(ToolCallback delegate, McpSyncClient mcpClient,
                                        String rawToolName, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.mcpClient = mcpClient;
        this.rawToolName = rawToolName;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    /**
     * 执行一次远端调用，保留身份与进度信息，并将图片转换为视觉分析引用。
     * @param toolInput 工具 JSON 参数
     * @param toolContext 当前会话上下文与进度令牌
     * @return 文本结果及图片引用，不含已保存图片的 Base64
     * @throws ToolExecutionException 远端调用或协议响应失败时抛出，不重放业务操作
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String progressToken = null;
        if (toolContext != null && toolContext.getContext() != null) {
            Object token = toolContext.getContext().get(MCP_PROGRESS_TOKEN_KEY);
            if (token instanceof String s && !s.isBlank()) {
                progressToken = s;
            }
        }
        McpToolResultCapture capture = McpToolResultCapture.from(toolContext);
        if (progressToken == null && capture == null) {
            return delegate.call(toolInput, toolContext);
        }
        McpSchema.CallToolResult result;
        try {
            // Apply identity forwarding BEFORE building CallToolRequest —
            // otherwise the progress path would silently bypass identity injection.
            String effectiveInput = toolInput;
            if (delegate instanceof IdentityForwardingToolCallback idFwd) {
                effectiveInput = idFwd.inject(toolInput, toolContext);
            }
            Map<String, Object> arguments = parseArguments(effectiveInput);
            Map<String, Object> metadata = McpWireMetadata.copy(toolContext);
            if (progressToken != null) metadata.put("progressToken", progressToken);
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                    .name(rawToolName)
                    .arguments(arguments != null ? arguments : Map.of())
                    .meta(metadata)
                    .build();
            result = mcpClient.callTool(request);
        } catch (Exception e) {
            // The server may have completed a side effect before the connection failed.
            throw new ToolExecutionException(getToolDefinition(), e);
        }
        if (capture != null) capture.capture(result, objectMapper);
        if (result != null && Boolean.TRUE.equals(result.isError())) {
            throw new ToolExecutionException(getToolDefinition(),
                    new IllegalStateException("Error calling tool: " + result.content()));
        }
        return serializeResult(result, toolContext);
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse MCP tool arguments as JSON, using raw string: {}", e.getMessage());
            return Map.of("input", toolInput);
        }
    }

    /**
     * 保留普通文本及结构化结果，将图片数据交给存储服务处理。
     * @param result 成功的 MCP 响应
     * @param context 当前会话上下文
     * @return 可交给文本模型的结果与图片分析指引
     */
    private String serializeResult(McpSchema.CallToolResult result, ToolContext context) {
        if (result == null) return "";
        if (result.content() == null || result.content().isEmpty()) {
            if (result.structuredContent() == null) return "";
            try {
                return objectMapper.writeValueAsString(result.structuredContent());
            } catch (Exception ignored) {
                return "[Structured tool result could not be serialized]";
            }
        }
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                sb.append(tc.text());
            } else if (content instanceof McpSchema.ImageContent image && imageStore != null) {
                sb.append(imageStore.describe(image, context));
            } else {
                sb.append(content.toString());
            }
        }
        return sb.toString();
    }

    /** Return the underlying delegate (for ReturnDirect / IdentityForward detection). */
    public ToolCallback getDelegate() {
        return delegate;
    }
}
