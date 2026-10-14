package vip.mate.tool.sandbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Executes untrusted code inside an ephemeral container. Never falls back to host execution. */
@Component
public class MemberSandboxExecutor {
    static final int OUTPUT_LIMIT = 1024 * 1024;
    private final String image;
    private final String docker;

    @Autowired
    public MemberSandboxExecutor(@Value("${mateclaw.workspace.sandbox.image:mateclaw-sandbox:local}") String image) {
        this(image, "docker");
    }

    MemberSandboxExecutor(String image, String docker) {
        if (image == null || image.isBlank() || image.startsWith("-")) {
            throw new IllegalArgumentException("A sandbox container image is required");
        }
        this.image = image;
        this.docker = docker;
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) { }

    public Result execute(List<String> command, Path memberRoot, Map<String, String> env, int timeout)
            throws IOException, InterruptedException {
        return execute(command, memberRoot, env, timeout, null);
    }

    private Result execute(List<String> command, Path memberRoot, Map<String, String> env, int timeout, String source)
            throws IOException, InterruptedException {
        String name = "mateclaw-member-" + UUID.randomUUID();
        List<String> arguments = command(command, memberRoot, env, name);
        if (source != null) arguments.add(2, "--interactive");
        int boundedTimeout = Math.max(1, Math.min(timeout, 300));
        Process process = null;
        Output stdout = null;
        Output stderr = null;
        try {
            process = new ProcessBuilder(arguments).start();
            // Source is streamed directly into the interpreter: no host script path exists
            // for a concurrent member container to replace with a symlink.
            OutputStream stdin = process.getOutputStream();
            if (source == null) stdin.close();
            else {
                Thread writer = new Thread(() -> {
                    try (stdin) { stdin.write(source.getBytes(StandardCharsets.UTF_8)); }
                    catch (IOException ignored) { }
                }, "member-sandbox-source");
                writer.setDaemon(true);
                writer.start();
            }
            stdout = new Output(process.getInputStream());
            stderr = new Output(process.getErrorStream());
            stdout.start();
            stderr.start();
            boolean finished = process.waitFor(boundedTimeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                removeContainer(name);
            }
            stdout.join(2000);
            stderr.join(2000);
            return new Result(finished ? process.exitValue() : -1, stdout.text(), stderr.text(), !finished);
        } finally {
            if (process != null) {
                process.destroyForcibly();
                // Preserve caller cancellation while giving Docker a bounded chance to remove the container.
                boolean interrupted = Thread.interrupted();
                try { removeContainer(name); }
                finally {
                    close(process.getInputStream());
                    close(process.getErrorStream());
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    public Result executeCode(String language, String code, Path memberRoot, List<String> args,
                              Map<String, String> env, int timeout) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        switch (language == null ? "" : language.toLowerCase(Locale.ROOT)) {
            case "python", "python3" -> command.addAll(List.of("python3", "-"));
            case "bash", "shell" -> command.addAll(List.of("bash", "-s", "--"));
            case "node", "javascript", "js" -> command.addAll(List.of("node", "-"));
            default -> throw new IllegalArgumentException("Unsupported sandbox language: " + language);
        }
        if (code == null || code.length() > 1024 * 1024) throw new IllegalArgumentException("Code must be supplied and at most 1 MiB");
        if (args != null) command.addAll(args);
        return execute(command, memberRoot, env, timeout, code);
    }

    /** 构造成员容器命令，挂载完整成员工作区而默认工作目录可定位到本轮产物目录。 */
    List<String> command(List<String> command, Path memberRoot, Map<String, String> env, String name) throws IOException {
        if (command == null || command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A sandbox command is required");
        }
        Path root = checkedRoot(memberRoot);
        Path workingDirectory = artifactWorkingDirectory(root, env);
        List<String> cmd = new ArrayList<>(List.of(docker, "run", "--rm", "--pull=never", "--name", name,
                "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network=none",
                "--pids-limit=64", "--memory=512m", "--memory-swap=512m", "--cpus=1",
                "--ulimit", "nofile=256:256", "--init", "--log-driver=none",
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777",
                "--mount", "type=bind,src=" + root + ",dst=" + root,
                "--workdir", workingDirectory.toString()));
        // Match the mounted directory owner; the default image contains no privileged executables.
        // Unix metadata is required to avoid silently choosing an unrelated container user.
        Object uid = Files.getAttribute(root, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        Object gid = Files.getAttribute(root, "unix:gid", LinkOption.NOFOLLOW_LINKS);
        cmd.addAll(List.of("--user", uid + ":" + gid, "--env", "HOME=/tmp", "--env", "TMPDIR=/tmp"));
        if (env != null) {
            for (var entry : env.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || entry.getValue() == null) {
                    throw new IllegalArgumentException("Invalid sandbox environment entry");
                }
                cmd.add("--env");
                cmd.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        // Override any image entrypoint; command arguments never pass through a host shell.
        cmd.addAll(List.of("--entrypoint", command.getFirst(), image));
        cmd.addAll(command.subList(1, command.size()));
        return cmd;
    }

    private static Path checkedRoot(Path memberRoot) throws IOException {
        if (memberRoot == null) throw new IllegalArgumentException("Member directory is required");
        Path root = memberRoot.toRealPath();
        if (!Files.isDirectory(root) || root.getParent() == null || root.toString().contains(",") || root.toString().contains("\n")) {
            throw new IllegalArgumentException("Invalid member directory");
        }
        return root;
    }

    /** 根据运行产物变量选择工作目录，成员工作区挂载边界保持不变。 */
    static Path artifactWorkingDirectory(Path memberRoot, Map<String, String> env) throws IOException {
        Path root = memberRoot.toRealPath();
        Path directory = env != null && env.containsKey("MATECLAW_ARTIFACT_ROOT")
                ? Path.of(env.get("MATECLAW_ARTIFACT_ROOT")).toRealPath() : root;
        if (!Files.isDirectory(directory) || !directory.startsWith(root)) {
            throw new IOException("Artifact directory is outside member workspace");
        }
        return directory;
    }

    private void removeContainer(String name) {
        Process cleanup = null;
        try {
            cleanup = new ProcessBuilder(docker, "rm", "--force", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(10, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (IOException ignored) {
            // Docker may be unavailable. Host execution is never attempted.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (cleanup != null && cleanup.isAlive()) cleanup.destroyForcibly();
        }
    }

    private static void close(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static final class Output extends Thread {
        private final InputStream stream;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private boolean truncated;
        Output(InputStream stream) { this.stream = stream; setDaemon(true); setName("member-sandbox-output"); }
        @Override public void run() {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (this) {
                        int keep = Math.min(count, OUTPUT_LIMIT - retained.size());
                        retained.write(buffer, 0, keep);
                        truncated |= keep < count;
                    }
                }
            } catch (IOException ignored) { }
        }
        synchronized String text() {
            return retained.toString(StandardCharsets.UTF_8) + (truncated ? "\n[output truncated at 1 MiB]" : "");
        }
    }
}
