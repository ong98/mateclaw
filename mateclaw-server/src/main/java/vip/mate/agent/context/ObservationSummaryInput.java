package vip.mate.agent.context;

import java.util.HashMap;
import java.util.List;
import vip.mate.agent.graph.executor.ToolResultStorage;

/** 为摘要模型提供独立预算的观察投影，原观察及推理历史保持完整。 */
public final class ObservationSummaryInput {
    /** 观察省略必须明确披露，模型不得据片段补全结果。 */
    private static final String OMITTED = "\n[观察片段不完整；省略内容见原文，不能推测或补全]\n";

    /** 禁止实例化无状态的摘要输入装配器。 */
    private ObservationSummaryInput() { }

    /**
     * 摘要输入及原文定位信息。
     * @param text 给摘要模型的观察正文
     * @param sourcePath 完整观察快照入口；未裁剪或归档失败时为空
     * @param originalChars 归档前完整观察正文的字符数
     * @param archiveFailed 超预算但无法归档，此时保留完整正文
     */
    public record Result(String text, String sourcePath, int originalChars, boolean archiveFailed) { }

    /**
     * 删除完全相同的重复正文；超预算时先归档整段观察，再为每轮分配有界首尾片段。
     * @param observations 按真实执行顺序保存的观察，不在此修改
     * @param storage 复用现有原文存储和成员隔离
     * @param conversationId 观察所属会话
     * @param snapshotId 本输入与摘要轮次的唯一标识
     * @param workspaceBasePath 经认证的工作区目录
     * @param maxTokens 扣除目标、进度和执行事实后的观察预算
     * @return 有界输入及可恢复原文；归档失败时保护原观察正文
     */
    public static Result prepare(List<String> observations, ToolResultStorage storage, String conversationId,
                                 String snapshotId, String workspaceBasePath, int maxTokens) {
        String complete = render(observations, Integer.MAX_VALUE);
        if (TokenEstimator.estimateTokens(complete) <= maxTokens) {
            return new Result(complete, null, complete.length(), false);
        }
        String path = storage == null ? null : storage.preserveForRecovery(
                complete, "summarizing_observations", snapshotId, conversationId, workspaceBasePath);
        if (path == null) return new Result(complete, null, complete.length(), true);
        String locator = "\n[完整观察快照] " + path
                + "\n这里只提供摘要输入片段；推理历史正文仍保留，缺少证据时只读恢复，不重放工具。\n";
        int allowance = Math.max(0, maxTokens - TokenEstimator.estimateTokens(locator));
        int low = 0;
        int high = observations.stream().mapToInt(String::length).max().orElse(0);
        String selected = render(observations, 0);
        if (TokenEstimator.estimateTokens(selected) > allowance) {
            return new Result(locator, path, complete.length(), false);
        }
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            String candidate = render(observations, middle);
            if (TokenEstimator.estimateTokens(candidate) <= allowance) {
                low = middle;
                selected = candidate;
            } else {
                high = middle - 1;
            }
        }
        return new Result(locator + selected, path, complete.length(), false);
    }

    /** 按轮次保留来源，相同正文指向首次出现的轮次，裁剪只作用于摘要投影。 */
    private static String render(List<String> observations, int bodyChars) {
        var firstSeen = new HashMap<String, Integer>();
        var text = new StringBuilder();
        for (int i = 0; i < observations.size(); i++) {
            String body = observations.get(i);
            Integer first = firstSeen.putIfAbsent(body, i + 1);
            text.append("【第 ").append(i + 1).append(" 轮观察】\n");
            if (first != null) {
                text.append("正文与第 ").append(first).append(" 轮完全相同；本轮执行顺序仍保留。\n");
            } else if (body.length() <= bodyChars) {
                text.append(body).append('\n');
            } else {
                int head = bodyChars / 3;
                text.append(StructuredTruncator.truncate(body, head, bodyChars - head, OMITTED)).append('\n');
            }
        }
        return text.toString();
    }
}
