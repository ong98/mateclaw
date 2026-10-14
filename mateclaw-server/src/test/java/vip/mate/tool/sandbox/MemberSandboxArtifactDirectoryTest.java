package vip.mate.tool.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 在所有操作系统验证默认产物目录选择，容器执行本身由 Linux 环境验证。 */
class MemberSandboxArtifactDirectoryTest {
    /** 当前轮次可使用工作区内子目录，同时拒绝将容器工作目录转到成员边界外。 */
    @Test
    void workingDirectoryStaysInsideMemberMount(@TempDir Path base) throws Exception {
        Path root = Files.createDirectory(base.resolve("member"));
        Path artifacts = Files.createDirectory(root.resolve("artifacts"));
        assertEquals(root.toRealPath(), MemberSandboxExecutor.artifactWorkingDirectory(root, Map.of()));
        assertEquals(artifacts.toRealPath(), MemberSandboxExecutor.artifactWorkingDirectory(root,
                Map.of("MATECLAW_ARTIFACT_ROOT", artifacts.toString())));
        assertThrows(java.io.IOException.class, () -> MemberSandboxExecutor.artifactWorkingDirectory(root,
                Map.of("MATECLAW_ARTIFACT_ROOT", base.toString())));
    }
}
