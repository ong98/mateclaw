package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 将发现的 MCP 工具适配为平台回调，并将原图转为视觉分析可读取的会话内引用。 */
final class ApplicationMcpToolCallback implements ToolCallback {
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};

    private final McpSyncClient client;
    private final McpJsonMapper protocolMapper;
    private final ToolDefinition definition;

    /** 图片存储；未配置时仅进行协议适配。 */
    private final McpImageStore imageStore;

    /**
     * 创建仅进行协议适配的回调。
     * @param client 当前 MCP 客户端
     * @param tool 已发现的工具元数据
     */
    ApplicationMcpToolCallback(McpSyncClient client, McpSchema.Tool tool) {
        this(client, tool, null);
    }

    /**
     * 创建支持会话图片存储的回调。
     * @param client 当前 MCP 客户端
     * @param tool 已发现的工具元数据
     * @param imageStore 图片存储服务；未提供时保留协议序列化行为
     */
    ApplicationMcpToolCallback(McpSyncClient client, McpSchema.Tool tool, McpImageStore imageStore) {
        this.imageStore = imageStore;
        this.client = Objects.requireNonNull(client, "MCP client must not be null");
        // Protocol numbers must remain numeric independently of MVC entity-ID serializers.
        this.protocolMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        Objects.requireNonNull(tool, "MCP tool must not be null");
        Map<String, Object> schema = protocolMapper.convertValue(tool.inputSchema(), MAP_TYPE);
        schema = schema != null ? new LinkedHashMap<>(schema) : new LinkedHashMap<>();
        schema.putIfAbsent("type", "object");
        if ("object".equals(schema.get("type"))) schema.putIfAbsent("properties", Map.of());
        try {
            this.definition = DefaultToolDefinition.builder()
                    .name(tool.name()).description(tool.description())
                    .inputSchema(protocolMapper.writeValueAsString(schema)).build();
        } catch (IOException ex) {
            throw new IllegalArgumentException("MCP tool schema cannot be serialized", ex);
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * 调用远端工具，将成功响应中的图片替换为可分析的引用，保留其他协议内容。
     * @param toolInput JSON 工具参数
     * @param toolContext 当前调用上下文
     * @return 序列化的工具内容，图片不再作为 Base64 文本返回
     * @throws IllegalArgumentException 参数不是 JSON 对象时抛出
     * @throws ToolExecutionException 远端调用或响应序列化失败时抛出
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> arguments;
        try {
            arguments = protocolMapper.readValue(toolInput == null || toolInput.isBlank() ? "{}" : toolInput, MAP_TYPE);
        } catch (IOException ex) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object", ex);
        }
        if (arguments == null) {
            throw new IllegalArgumentException("MCP tool arguments must be a JSON object");
        }
        Map<String, Object> metadata = toolContext != null ? McpWireMetadata.copy(toolContext) : null;
        try {
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name(definition.name()).arguments(arguments).meta(metadata).build());
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("Error calling tool: " + result.content());
            }
            if (imageStore == null || result.content() == null) {
                return protocolMapper.writeValueAsString(result.content());
            }
            java.util.List<McpSchema.Content> content = new java.util.ArrayList<>();
            for (McpSchema.Content part : result.content()) {
                content.add(part instanceof McpSchema.ImageContent image
                        ? new McpSchema.TextContent(imageStore.describe(image, toolContext)) : part);
            }
            return protocolMapper.writeValueAsString(content);
        } catch (Exception ex) {
            throw new ToolExecutionException(definition, ex);
        }
    }
}
