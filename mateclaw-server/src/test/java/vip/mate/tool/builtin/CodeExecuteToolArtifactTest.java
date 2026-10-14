package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * execute_code embeds artifact download links into its result; the chat layer
 * then extracts them (issue #191). This pins the link shape so a JSON-array
 * regression can't sneak back: an array's own '[' sits next to the markdown '['
 * and the extractor would capture '"[name' as the filename.
 */
class CodeExecuteToolArtifactTest {
    /** 两会话与同会话新输入并发生成同名文件时，正文和下载入口保持独立。 */
    @Test
    void concurrentInputsKeepSameNamedReportsIndependent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        var execution = org.mockito.Mockito.mock(vip.mate.skill.runtime.SkillScriptExecutionService.class);
        var cache = new vip.mate.tool.document.GeneratedFileCache(root.resolve("cache"));
        var first = vip.mate.agent.context.ChatOrigin.web("conv-a", "user", 1L, root.toString()).withOriginMessageId(1L);
        var origins = List.of(first, first.withOriginMessageId(2L), first.withConversationId("conv-b"));
        var rendezvous = new java.util.concurrent.CountDownLatch(origins.size());
        org.mockito.Mockito.when(execution.executeCode(org.mockito.ArgumentMatchers.eq("python"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.isNull())).thenAnswer(invocation -> {
                    java.nio.file.Path cwd = invocation.getArgument(2);
                    java.util.Map<String, String> env = invocation.getArgument(4);
                    assertEquals(cwd.toString(), env.get("MATECLAW_ARTIFACT_ROOT"));
                    rendezvous.countDown();
                    org.junit.jupiter.api.Assertions.assertTrue(rendezvous.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    String label = invocation.getArgument(1);
                    java.nio.file.Files.writeString(cwd.resolve("report.csv"), label);
                    return vip.mate.skill.runtime.SkillScriptExecutionService.ScriptResult.error(0, label);
                });
        var tool = new CodeExecuteTool(null, null, execution, null, new com.fasterxml.jackson.databind.ObjectMapper(), cache);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(origins.size());
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<String>>();
            for (var origin : origins) tasks.add(pool.submit(() -> tool.execute_code("python",
                    origin.conversationId() + ":" + origin.originMessageId(), null, null, null, origin.toToolContext())));
            var urls = new java.util.HashSet<String>();
            for (int i = 0; i < origins.size(); i++) {
                var origin = origins.get(i);
                String result = tasks.get(i).get(15, java.util.concurrent.TimeUnit.SECONDS);
                Matcher link = LINK.matcher(result);
                org.junit.jupiter.api.Assertions.assertTrue(link.find(), result);
                org.junit.jupiter.api.Assertions.assertTrue(urls.add(link.group(2)), "下载入口不能串用");
                assertEquals(origin.conversationId() + ":" + origin.originMessageId(), java.nio.file.Files.readString(
                        vip.mate.agent.context.RuntimeArtifacts.directory(origin).resolve("report.csv")));
            }
            org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(root.resolve("report.csv")));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 默认执行目录与环境变量指向本轮目录，隐藏运行根目录下的报告也能展示下载链接。 */
    @Test
    void executionProducesDownloadInScopedArtifactDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        var execution = org.mockito.Mockito.mock(vip.mate.skill.runtime.SkillScriptExecutionService.class);
        var cache = new vip.mate.tool.document.GeneratedFileCache(root.resolve("cache"));
        var origin = vip.mate.agent.context.ChatOrigin.web("conv-report", "user", 1L, root.toString())
                .withOriginMessageId(1L);
        org.mockito.Mockito.when(execution.executeCode(org.mockito.ArgumentMatchers.eq("python"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.isNull())).thenAnswer(invocation -> {
                    java.nio.file.Path cwd = invocation.getArgument(2);
                    java.util.Map<String, String> env = invocation.getArgument(4);
                    assertEquals(vip.mate.agent.context.RuntimeArtifacts.directory(origin), cwd);
                    assertEquals(cwd.toString(), env.get("MATECLAW_ARTIFACT_ROOT"));
                    java.nio.file.Files.writeString(cwd.resolve("report.csv"), "案件,结果\n1,通过");
                    return vip.mate.skill.runtime.SkillScriptExecutionService.ScriptResult.error(0, "完成");
                });
        var tool = new CodeExecuteTool(null, null, execution, null, new com.fasterxml.jackson.databind.ObjectMapper(), cache);
        String result = tool.execute_code("python", "print('report')", null, null, null, origin.toToolContext());
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("[report.csv]"), result);
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(root.resolve("report.csv")));
    }


    // Mirror of ChatController.GENERATED_FILE_LINK_PATTERN.
    private static final Pattern LINK = Pattern.compile(
            "\\[([^\\]]+)\\]\\(((?:https?://[^/\\s)\\]]+)?/api/v1/files/generated/[A-Za-z0-9-]+)\\)");

    @Test
    @DisplayName("formatResult embeds links so extraction yields the clean filename, not '\"[name'")
    void formatResultExtractsCleanFilename() {
        CodeExecuteTool tool = new CodeExecuteTool(null, null, null, null, null, null);
        var result = vip.mate.skill.runtime.SkillScriptExecutionService.ScriptResult.error(0, "");
        String out = tool.formatResult(result, List.of(
                "[report.csv](http://localhost:18088/api/v1/files/generated/abc-123)",
                "[data.xlsx](http://localhost:18088/api/v1/files/generated/def-456)"));

        List<String> names = new ArrayList<>();
        Matcher m = LINK.matcher(out);
        while (m.find()) {
            names.add(m.group(1));
        }
        assertEquals(List.of("report.csv", "data.xlsx"), names,
                "extracted filenames must be clean, full result was: " + out);
    }

    @Test
    @DisplayName("No artifacts → no generatedFiles field")
    void noArtifactsNoField() {
        CodeExecuteTool tool = new CodeExecuteTool(null, null, null, null, null, null);
        var result = vip.mate.skill.runtime.SkillScriptExecutionService.ScriptResult.error(0, "ok");
        String out = tool.formatResult(result, List.of());
        assertEquals(false, out.contains("generatedFiles"), out);
    }
}
