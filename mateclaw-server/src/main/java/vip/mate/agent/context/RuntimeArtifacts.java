package vip.mate.agent.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.workspace.core.service.MemberFileAccess;
import vip.mate.workspace.core.service.MemberFileIsolation;

/** 提供当前输入轮次的默认产物目录，业务工作区和用户指定输出路径仍可共享。 */
public final class RuntimeArtifacts {
    /** 禁止实例化无状态的运行目录工具。 */
    private RuntimeArtifacts() { }

    /**
     * 根据已认证来源定位默认产物目录，审批续跑复用原输入轮次。
     * @param origin 当前会话及成员工作区
     * @return 按会话和输入消息隔离的绝对目录，缺少来源时为空
     */
    public static Path directory(ChatOrigin origin) {
        if (origin == null || origin.conversationId() == null || origin.conversationId().isBlank()
                || origin.originMessageId() == null) return null;
        Path workspace = WorkspacePathGuard.getWorkingDirectory(origin.toToolContext());
        if (workspace == null) return null;
        String conversation = origin.conversationId().replaceAll("[^a-zA-Z0-9_-]", "_");
        return workspace.toAbsolutePath().normalize()
                .resolve(".mateclaw/artifacts").resolve(conversation).resolve(origin.originMessageId().toString());
    }

    /**
     * 创建经路径守卫验证的本轮产物目录。
     * @param origin 已认证的输入来源
     * @return 已创建目录，缺少轮次来源时为空
     * @throws IOException 目录无法安全创建
     */
    public static Path prepare(ChatOrigin origin) throws IOException {
        Path directory = directory(origin);
        if (directory == null) return null;
        WorkspacePathGuard.validatePath(directory.toString(), origin.toToolContext());
        if (MemberFileIsolation.isEnabled()) {
            MemberFileAccess.write(Path.of(origin.workspaceBasePath()), directory.resolve(".runtime"), new byte[0], false);
        } else {
            Files.createDirectories(directory);
        }
        return directory;
    }
}
