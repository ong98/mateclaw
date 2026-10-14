package vip.mate.agent.graph.executor;

import vip.mate.workspace.core.service.MemberFileIsolation;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.context.StructuredTruncator;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 通用工具结果存储，按单结果和批次预算落盘，并为旧回执提供只读恢复入口。
 * <p>同一调用的原文首次保存后不可被裁剪正文覆盖；本轮回执索引随实际完成批次更新。
 * 成员隔离开启时只使用已认证成员目录，否则依次使用配置目录、工作区或临时目录。
 * 保存失败时保留调用原文；回执和索引复用已有会话删除及保留期清理机制。</p>
 */
@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(ToolResultProperties.class)
public class ToolResultStorage {

    /** Marker placed in the in-context preview so callers and tools can recognize spill output. */
    public static final String SPILL_MARKER_PREFIX = "[mate-tool-result-spill]";

    private final ToolResultProperties props;
    /** Cached at construction; refreshed lazily if the underlying list mutates (rare). */
    private volatile java.util.Set<String> excludedToolsSnapshot;

    /** D-6: monotonically increasing spill counter for observability. */
    private final java.util.concurrent.atomic.AtomicLong spillCount = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Workspace roots observed during this JVM's lifetime. Populated every
     * time a successful spill resolves a base directory; consulted by the
     * scheduled retention sweep and by {@link #purgeConversation} so we
     * don't have to query the database for every workspace path. Cross-JVM
     * orphans are not covered — that is documented in the cleanup javadoc.
     */
    private final java.util.Set<Path> observedRoots = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ToolResultStorage(ToolResultProperties props) {
        this.props = props;
        this.excludedToolsSnapshot = props.excludedToolsSet();
    }

    /**
     * Trust the deterministic spill roots with the workspace path guard at
     * startup, before any spill happens in this JVM. Without this, a
     * conversation that spilled in a previous run and is then resumed after a
     * restart would have its {@code read_file} of the still-on-disk spill path
     * rejected as a boundary escape until the next spill re-registers the root.
     * The per-workspace branch ({@code <workspace>/.mateclaw/tool-results}) is
     * intentionally not registered here — it already sits inside its own
     * workspace boundary.
     */
    @PostConstruct
    void registerSpillRootsAsTrusted() {
        if (!props.isEnabled()) {
            return;
        }
        if (!props.getStorageBaseDir().isEmpty()) {
            WorkspacePathGuard.addTrustedRoot(props.getStorageBaseDir());
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            WorkspacePathGuard.addTrustedRoot(Paths.get(tmp, "mateclaw", "tool-results").toString());
        }
    }

    /** D-6: current cumulative spill count (monotonically increasing). */
    public long getSpillCount() {
        return spillCount.get();
    }

    /**
     * Public view of the exclusion list for callers outside this class (e.g.
     * the executor's spill/truncate dispatcher). Retrieval-style tools on this
     * list must have their full output preserved: they are never spilled here
     * (Layer 2) and never inline-truncated by the executor — a partial
     * {@code SKILL.md} / {@code read_file} body makes the model act on
     * incomplete data, and weak models fabricate the omitted span instead of
     * heeding the fidelity note. The aggregate turn budget (Layer 3) remains
     * the only place an excluded result may be compacted, and only as a last
     * resort when nothing else can free budget.
     */
    public boolean isRetrievalExcluded(String toolName) {
        return isExcluded(toolName);
    }

    /**
     * Returns true when {@code toolName} is in the configured exclusion list.
     * Excluded tools (typically retrieval tools like {@code read_file}) are
     * never spilled — spilling their output would create a recursion where
     * the agent reads a spill path and produces yet another spill.
     */
    private boolean isExcluded(String toolName) {
        if (toolName == null) return false;
        java.util.Set<String> snap = excludedToolsSnapshot;
        java.util.Set<String> live = props.excludedToolsSet();
        if (live != snap && !live.equals(snap)) {
            this.excludedToolsSnapshot = live;
            snap = live;
        }
        return snap.contains(toolName);
    }

    /**
     * Layer 2. If {@code result} exceeds the per-result threshold, write the full
     * text to a spill file and return a preview-plus-pointer string. Otherwise
     * return the original result unchanged.
     *
     * @param result          the raw tool output (may be null)
     * @param toolName        used in the preview header so the LLM knows which tool produced it
     * @param toolUseId       unique within a conversation; becomes the spill file's basename
     * @param conversationId  scopes spill files by conversation
     * @param workspaceBasePath agent's workspace base path; may be null/blank
     */
    public String persistIfOversized(String result, String toolName, String toolUseId,
                                     String conversationId, String workspaceBasePath) {
        if (!props.isEnabled() || result == null) {
            return result;
        }
        if (isExcluded(toolName)) {
            // Retrieval-style tool — never spill, would cause read-back recursion.
            return result;
        }
        if (result.length() <= props.getPerResultThresholdChars()) {
            return result;
        }
        Path file = spillFor(conversationId, toolUseId, workspaceBasePath);
        if (file == null) {
            return result;
        }
        try {
            writeSpill(file, result, workspaceBasePath);
        } catch (IOException ioe) {
            log.warn("[ToolResultStorage] spill write failed for tool={} convId={} ({}); keeping original",
                    toolName, conversationId, ioe.getMessage());
            return result;
        }
        long count = spillCount.incrementAndGet();
        log.info("[ToolResultStorage] spill #{}: tool={} chars={} convId={}", count, toolName, result.length(), conversationId);
        return buildPreview(result, toolName, file);
    }

    /**
     * 保存工具原始回执，供上下文压缩后的只读恢复；读取工具沿用原文件入口。
     * 已落盘结果直接复用引用，不把预览覆盖到原始文件中。
     *
     * @param result 本次调用返回的完整正文或已有落盘标记
     * @param toolName 实际执行的工具名称
     * @param toolUseId 本次工具调用标识
     * @param conversationId 回执所属会话
     * @param workspaceBasePath 当前工作区隔离目录
     * @return 原文绝对路径；存储关闭、读取工具或保存失败时返回 {@code null}
     */
    public String preserveForRecovery(String result, String toolName, String toolUseId,
                                      String conversationId, String workspaceBasePath) {
        if (!props.isEnabled() || result == null || conversationId == null || conversationId.isBlank()
                || toolUseId == null || toolUseId.isBlank() || isExcluded(toolName)) return null;
        String existing = spillPath(result);
        if (existing != null) return existing;
        Path file = spillFor(conversationId, toolUseId, workspaceBasePath);
        if (file == null) return null;
        try {
            writeSpill(file, result, workspaceBasePath);
            return file.toAbsolutePath().toString();
        } catch (IOException | RuntimeException ex) {
            log.warn("[ToolResultStorage] receipt write failed for tool={} convId={}: {}",
                    toolName, conversationId, ex.getMessage());
            return null;
        }
    }

    /**
     * 更新本轮回执索引，让超出最近展示范围的调用仍可按调用标识找回。
     *
     * @param index 本轮回执的 JSON 索引，不包含大段原文
     * @param runKey 本轮首条调用标识，用于区分同一会话中的不同执行轮次
     * @param conversationId 索引所属会话
     * @param workspaceBasePath 当前工作区隔离目录
     * @return 索引绝对路径；保存失败时返回 {@code null}
     */
    public String preserveRecoveryIndex(String index, String runKey, String conversationId,
                                         String workspaceBasePath) {
        if (!props.isEnabled() || conversationId == null || conversationId.isBlank()
                || runKey == null || runKey.isBlank()) return null;
        Path file = spillFor(conversationId, "receipt-index-" + runKey, workspaceBasePath);
        if (file == null) return null;
        try {
            if (MemberFileIsolation.isEnabled()) {
                vip.mate.workspace.core.service.MemberFileAccess.write(Path.of(workspaceBasePath),
                        file, index.getBytes(StandardCharsets.UTF_8), false);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, index, StandardCharsets.UTF_8);
            }
            return file.toAbsolutePath().toString();
        } catch (IOException | RuntimeException ex) {
            log.warn("[ToolResultStorage] receipt index write failed for convId={}: {}", conversationId, ex.getMessage());
            return null;
        }
    }

    /**
     * 从落盘标记读取原文路径，保留包含空格的完整路径。
     *
     * @param result 工具结果正文
     * @return 标记中的原文路径；普通结果或标记缺少路径时返回 {@code null}
     */
    public static String spillPath(String result) {
        if (result == null || !result.startsWith(SPILL_MARKER_PREFIX)) return null;
        int start = result.indexOf(" path=");
        if (start < 0) return null;
        int end = result.indexOf('\n', start);
        String path = (end < 0 ? result.substring(start + 6) : result.substring(start + 6, end)).trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * 将旧工具正文替换为紧凑的只读恢复引用；保存失败时保留原文。
     *
     * @param result 当前尚未裁剪的工具正文
     * @param toolName 实际工具名称
     * @param toolUseId 工具调用标识
     * @param conversationId 当前会话
     * @param workspaceBasePath 当前工作区隔离目录
     * @return 原文恢复标记，或无法保存时的原始正文
     */
    public String compactForRecovery(String result, String toolName, String toolUseId,
                                      String conversationId, String workspaceBasePath) {
        if (spillPath(result) != null) return result;
        String path = preserveForRecovery(result, toolName, toolUseId, conversationId, workspaceBasePath);
        if (path == null) return result;
        String reference = SPILL_MARKER_PREFIX + " tool=" + toolName + " full_chars=" + result.length()
                + " path=" + path + "\n原始回执已保存；需要原文时用 read_file 读取该路径。";
        return reference.length() < result.length() ? reference : result;
    }

    /**
     * Layer 3. Walk the responses; if their aggregate length exceeds the turn
     * budget, spill the largest remaining non-spilled result and recompute.
     * Mutates the returned list in place by replacing oversized responses.
     */
    public List<ToolResponseMessage.ToolResponse> enforceTurnBudget(
            List<ToolResponseMessage.ToolResponse> responses,
            String conversationId,
            String workspaceBasePath) {
        if (!props.isEnabled() || responses == null || responses.isEmpty()) {
            return responses;
        }
        int budget = props.getPerTurnBudgetChars();
        int aggregate = aggregateSize(responses);
        if (aggregate <= budget) {
            return responses;
        }

        log.info("[ToolResultStorage] turn budget exceeded: {} chars > {} (responses={})",
                aggregate, budget, responses.size());

        List<ToolResponseMessage.ToolResponse> mutable = new ArrayList<>(responses);

        while (aggregate > budget) {
            // Find the largest response that has not yet been spilled and is
            // not produced by an excluded (retrieval-style) tool.
            int targetIdx = -1;
            int targetLen = -1;
            for (int i = 0; i < mutable.size(); i++) {
                ToolResponseMessage.ToolResponse r = mutable.get(i);
                String body = r.responseData();
                if (body == null || body.startsWith(SPILL_MARKER_PREFIX)) continue;
                if (isExcluded(r.name())) continue;     // retrieval tools must not be spilled
                if (body.length() > targetLen) {
                    targetLen = body.length();
                    targetIdx = i;
                }
            }
            if (targetIdx < 0) {
                int compactedIdx = compactLargestExcludedResult(mutable);
                if (compactedIdx >= 0) {
                    aggregate = aggregateSize(mutable);
                    continue;
                }
                log.warn("[ToolResultStorage] aggregate still {} chars after spilling/compacting everything eligible",
                        aggregate);
                break;
            }
            ToolResponseMessage.ToolResponse target = mutable.get(targetIdx);
            Path file = spillFor(conversationId, target.id(), workspaceBasePath);
            if (file == null) {
                break;
            }
            try {
                writeSpill(file, target.responseData(), workspaceBasePath);
            } catch (IOException ioe) {
                log.warn("[ToolResultStorage] spill write failed during turn budget enforcement: {}",
                        ioe.getMessage());
                break;
            }
            String preview = buildPreview(target.responseData(), target.name(), file);
            mutable.set(targetIdx, new ToolResponseMessage.ToolResponse(target.id(), target.name(), preview));
            aggregate = aggregateSize(mutable);
        }
        return mutable;
    }

    private int compactLargestExcludedResult(List<ToolResponseMessage.ToolResponse> mutable) {
        int targetIdx = -1;
        int targetLen = props.getExcludedToolInlineChars();
        for (int i = 0; i < mutable.size(); i++) {
            ToolResponseMessage.ToolResponse r = mutable.get(i);
            String body = r.responseData();
            if (body == null || body.startsWith(SPILL_MARKER_PREFIX)) continue;
            if (!isExcluded(r.name())) continue;
            if (body.length() > targetLen) {
                targetLen = body.length();
                targetIdx = i;
            }
        }
        if (targetIdx < 0) {
            return -1;
        }
        ToolResponseMessage.ToolResponse target = mutable.get(targetIdx);
        String compacted = compactInline(target.responseData(), target.name(), props.getExcludedToolInlineChars());
        mutable.set(targetIdx, new ToolResponseMessage.ToolResponse(target.id(), target.name(), compacted));
        log.info("[ToolResultStorage] compacted excluded tool result: tool={} chars={} -> {}",
                target.name(), targetLen, compacted.length());
        return targetIdx;
    }

    static String compactInline(String body, String toolName, int maxChars) {
        if (body == null || body.length() <= maxChars) {
            return body;
        }
        String marker = "\n\n... [tool result compacted for model context: tool="
                + toolName + ", original_chars=" + body.length() + ". "
                + StructuredTruncator.FIDELITY_NOTE + "] ...\n\n";
        int available = Math.max(200, maxChars - marker.length());
        int headLen = Math.max(100, (int) (available * 0.45));
        int tailLen = Math.max(100, available - headLen);
        if (headLen + tailLen >= body.length()) {
            return body;
        }
        return StructuredTruncator.truncate(body, headLen, tailLen, marker);
    }

    private static int aggregateSize(List<ToolResponseMessage.ToolResponse> responses) {
        int sum = 0;
        for (ToolResponseMessage.ToolResponse r : responses) {
            if (r.responseData() != null) sum += r.responseData().length();
        }
        return sum;
    }

    private String buildPreview(String fullResult, String toolName, Path spillFile) {
        // Snap the preview to a complete JSON element so the model never sees a value
        // severed mid-token (which invites it to fabricate the omitted fields).
        String head = StructuredTruncator.headSlice(fullResult, props.getPreviewHeadChars());
        return SPILL_MARKER_PREFIX
                + " tool=" + toolName
                + " full_chars=" + fullResult.length()
                + " path=" + spillFile.toAbsolutePath()
                + "\n[Preview — first " + head.length() + " of " + fullResult.length()
                + " chars. The preview is INCOMPLETE: use read_file with the path above to "
                + "retrieve the full result. Do NOT infer or fabricate the omitted content.]\n"
                + head
                + "\n…[truncated]";
    }

    /**
     * 首次写入调用原文，重复压缩复用已有文件，避免将裁剪正文覆盖到完整回执。
     * @param file 当前调用的存储路径
     * @param text 尚未裁剪的正文
     * @param workspaceBasePath 当前成员隔离目录
     * @throws IOException 原文无法保存且不是已有回执
     */
    private void writeSpill(Path file, String text, String workspaceBasePath) throws IOException {
        try {
            if (MemberFileIsolation.isEnabled()) {
                vip.mate.workspace.core.service.MemberFileAccess.writeNew(Path.of(workspaceBasePath), file, text.getBytes(StandardCharsets.UTF_8));
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        } catch (java.nio.file.FileAlreadyExistsException existingReceipt) {
            if (MemberFileIsolation.isEnabled()) MemberFileIsolation.validate(Path.of(workspaceBasePath), file.toString());
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw existingReceipt;
        }
    }

    private Path spillFor(String conversationId, String toolUseId, String workspaceBasePath) {
        String safeConv = sanitize(conversationId);
        String safeId = sanitize(toolUseId);
        if (safeId.isEmpty()) {
            safeId = "noid-" + System.nanoTime();
        }
        Path base = resolveBaseDir(workspaceBasePath);
        if (base == null) return null;
        return base.resolve(safeConv).resolve(safeId + ".txt");
    }

    /**
     * 解析工具媒体与文本共用的会话目录，沿用工作区隔离和清理生命周期。
     *
     * @param conversationId 当前会话标识，不能为空
     * @param workspaceBasePath 当前工作区目录；未绑定时使用配置目录或临时目录
     * @return 会话目录；缺少会话或隔离目录时返回 {@code null}
     */
    public Path resolveConversationDirectory(String conversationId, String workspaceBasePath) {
        if (conversationId == null || conversationId.isBlank()) return null;
        Path base = resolveBaseDir(workspaceBasePath);
        return base == null ? null : base.resolve(sanitize(conversationId));
    }

    private Path resolveBaseDir(String workspaceBasePath) {
        Path base;
        boolean outsideWorkspace;
        if (MemberFileIsolation.isEnabled()) {
            if (workspaceBasePath == null || workspaceBasePath.isBlank()) return null;
            base = Paths.get(workspaceBasePath, ".mateclaw", "tool-results");
            outsideWorkspace = false;
        } else if (!props.getStorageBaseDir().isEmpty()) {
            base = Paths.get(props.getStorageBaseDir());
            outsideWorkspace = true;
        } else if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
            // Inside the workspace boundary already — read_file of these spill
            // files is permitted without an extra trusted-root registration.
            base = Paths.get(workspaceBasePath, ".mateclaw", "tool-results");
            outsideWorkspace = false;
        } else {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null || tmp.isEmpty()) return null;
            base = Paths.get(tmp, "mateclaw", "tool-results");
            outsideWorkspace = true;
        }
        // Register so the retention sweep and conversation-delete hook can
        // reach this root even when the workspace path is no longer in scope.
        observedRoots.add(base);
        // A spill directory that lives outside the workspace must be trusted by
        // the path guard; otherwise the read_file the spill preview tells the
        // agent to perform is rejected as a workspace-boundary escape.
        if (outsideWorkspace) {
            WorkspacePathGuard.addTrustedRoot(base.toString());
        }
        return base;
    }

    /**
     * Roots currently known to this instance. Exposed package-private so the
     * scheduled retention sweep and unit tests can enumerate them without
     * touching the underlying set directly.
     */
    java.util.Set<Path> getObservedRoots() {
        return java.util.Collections.unmodifiableSet(observedRoots);
    }

    /**
     * Best-effort: delete every spill file and per-conversation directory
     * older than {@link ToolResultProperties#getRetentionDays()} across all
     * roots this storage has seen, plus the configured base dir and the
     * tmpdir fallback. Returns the number of files deleted.
     *
     * <p>Workspaces that never received a spill in this JVM's lifetime are
     * not covered. Persisting an observed-roots registry across restarts
     * could fix that, but is intentionally out of scope — the operator-side
     * remedy is to run a one-off cleanup with {@code storage-base-dir}
     * pointed at the historical workspace.
     */
    public int cleanupExpired() {
        // Legacy traversal cannot safely follow directories writable by confined code.
        if (MemberFileIsolation.isEnabled()) return 0;
        if (props.getRetentionDays() <= 0) {
            return 0;
        }
        long cutoffEpochMillis = System.currentTimeMillis()
                - (long) props.getRetentionDays() * 24L * 60L * 60L * 1000L;

        java.util.Set<Path> roots = new java.util.LinkedHashSet<>(observedRoots);
        if (!props.getStorageBaseDir().isEmpty()) {
            roots.add(Paths.get(props.getStorageBaseDir()));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            roots.add(Paths.get(tmp, "mateclaw", "tool-results"));
        }

        int deleted = 0;
        for (Path root : roots) {
            deleted += deleteExpiredUnder(root, cutoffEpochMillis);
        }
        if (deleted > 0) {
            log.info("[ToolResultStorage] cleanup: {} spill files removed across {} root(s)",
                    deleted, roots.size());
        }
        return deleted;
    }

    private int deleteExpiredUnder(Path root, long cutoffEpochMillis) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(root, 2)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(root)) continue;
                if (!java.nio.file.Files.isRegularFile(p)) continue;
                try {
                    long mtime = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                    if (mtime < cutoffEpochMillis) {
                        java.nio.file.Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (java.io.IOException ioe) {
                    log.warn("[ToolResultStorage] failed to inspect spill file {}: {}", p, ioe.getMessage());
                }
            }
        } catch (java.io.IOException ioe) {
            log.warn("[ToolResultStorage] cleanup walk failed under {}: {}", root, ioe.getMessage());
            return deleted;
        }
        // Best-effort: remove emptied per-conversation directories.
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(root)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (!java.nio.file.Files.isDirectory(child)) continue;
                try (java.util.stream.Stream<Path> kids = java.nio.file.Files.list(child)) {
                    if (kids.findAny().isEmpty()) {
                        java.nio.file.Files.deleteIfExists(child);
                    }
                } catch (java.io.IOException ignored) {
                    // empty-check failure is not fatal — leave the directory alone
                }
            }
        } catch (java.io.IOException ioe) {
            log.warn("[ToolResultStorage] empty-dir sweep failed under {}: {}", root, ioe.getMessage());
        }
        return deleted;
    }

    /**
     * Delete every spill file produced for {@code conversationId} across
     * all observed roots, plus the configured base and tmpdir fallback.
     * Called by {@code ConversationService.deleteConversation} so spill
     * directories don't outlive the conversation that owns them.
     *
     * <p>Silently no-ops when nothing matches — a conversation that never
     * spilled, or one whose workspace root was never observed in this JVM,
     * is simply left alone. Returns the number of files deleted.
     */
    public int purgeConversation(String conversationId) {
        if (MemberFileIsolation.isEnabled()) return 0;
        if (conversationId == null || conversationId.isEmpty()) {
            return 0;
        }
        String safeConv = sanitize(conversationId);
        java.util.Set<Path> roots = new java.util.LinkedHashSet<>(observedRoots);
        if (!props.getStorageBaseDir().isEmpty()) {
            roots.add(Paths.get(props.getStorageBaseDir()));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            roots.add(Paths.get(tmp, "mateclaw", "tool-results"));
        }
        int deleted = 0;
        for (Path root : roots) {
            Path convDir = root.resolve(safeConv);
            if (!java.nio.file.Files.isDirectory(convDir)) continue;
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(convDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    try {
                        if (java.nio.file.Files.isRegularFile(p)) {
                            java.nio.file.Files.deleteIfExists(p);
                            deleted++;
                        }
                    } catch (java.io.IOException ioe) {
                        log.warn("[ToolResultStorage] failed to delete spill file {}: {}", p, ioe.getMessage());
                    }
                }
            } catch (java.io.IOException ioe) {
                log.warn("[ToolResultStorage] purge walk failed under {}: {}", convDir, ioe.getMessage());
            }
            try {
                java.nio.file.Files.deleteIfExists(convDir);
            } catch (java.io.IOException ignored) {
                // non-empty after deletes (another writer raced us) — fine, leave it
            }
        }
        if (deleted > 0) {
            log.info("[ToolResultStorage] purged {} spill file(s) for conversation {}", deleted, conversationId);
        }
        return deleted;
    }

    /**
     * Strip path separators and reserved characters so user-supplied IDs cannot
     * escape the directory. Delegates to the canonical conversation-id sanitizer
     * so every id → path-segment mapping across the codebase stays byte-for-byte
     * identical (see issue #507).
     */
    private static String sanitize(String s) {
        return ChatUploadLocationResolver.sanitizeSegment(s);
    }

    /** Test/admin helper: lexicographic ordering by length, descending. Not used at runtime. */
    static Comparator<ToolResponseMessage.ToolResponse> byBodyLengthDesc() {
        return (a, b) -> Integer.compare(
                b.responseData() == null ? 0 : b.responseData().length(),
                a.responseData() == null ? 0 : a.responseData().length());
    }
}
