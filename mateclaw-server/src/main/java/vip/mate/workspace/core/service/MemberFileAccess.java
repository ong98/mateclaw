package vip.mate.workspace.core.service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** 通过安全目录句柄读写成员文件，拒绝跟随链接；不支持安全句柄的文件系统不降级访问。 */
public final class MemberFileAccess {
    private MemberFileAccess() { }
    public static byte[] read(Path root, Path file, int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("Invalid read limit");
        return withParent(root, file, false, (parent, name) -> readFile(parent, name, limit));
    }

    static byte[] readFile(SecureDirectoryStream<Path> parent, Path name, int limit) throws IOException {
        var attrs = parent.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attrs.isRegularFile()) throw new IOException("Only regular member files may be read");
        // Linux O_RDWR opens a substituted FIFO without waiting for a writer. Never read
        // beyond the initial fstat size (FIFO size is zero), so a leaf swap cannot hang.
        // This deliberately requires read/write permission for host-side snapshots.
        try (var channel = parent.newByteChannel(name, Set.of(StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            long remaining = channel.size();
            if (remaining < 0 || remaining > limit) throw new IOException("Member file exceeds read limit");
            var bytes = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (remaining > 0) {
                buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int count = channel.read(buffer);
                if (count < 0) break;
                bytes.write(buffer.array(), 0, count); remaining -= count;
            }
            return bytes.toByteArray();
        }
    }

    public static void write(Path root, Path file, byte[] bytes, boolean append) throws IOException {
        withParent(root, file, true, (parent, name) -> {
            byte[] prefix = new byte[0];
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = null;
            try {
                var attrs = parent.getFileAttributeView(name, java.nio.file.attribute.PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attrs.isRegularFile()) throw new IOException("Only regular member files may be written");
                permissions = attrs.permissions();
                if (append) prefix = readFile(parent, name, 32 * 1024 * 1024);
            } catch (NoSuchFileException newFile) { }
            // Write a private regular file outside the member mount, then replace the
            // directory entry atomically. Never open a potentially substituted FIFO/device.
            Path trustedParent = root.toAbsolutePath().getParent();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(trustedParent)) {
                if (!(stream instanceof SecureDirectoryStream<Path> staging)) throw new IOException("SecureDirectoryStream required");
                Path temporary = Files.createTempFile(trustedParent, ".member-file-", ".tmp").getFileName();
                try {
                    try (var channel = staging.newByteChannel(temporary, Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                        for (byte[] part : new byte[][]{prefix, bytes}) {
                            ByteBuffer buffer = ByteBuffer.wrap(part);
                            while (buffer.hasRemaining()) channel.write(buffer);
                        }
                    }
                    if (permissions != null) staging.getFileAttributeView(temporary,
                            java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setPermissions(permissions);
                    staging.move(temporary, parent, name);
                } finally {
                    try { staging.deleteFile(temporary); } catch (NoSuchFileException moved) { }
                }
            }
            return null;
        });
    }
    /**
     * 在成员目录内首次创建不可覆盖的回执文件，拒绝跟随链接或替换已有数据。
     * @param root 已认证成员的文件根目录
     * @param file 回执文件路径
     * @param bytes 完整回执内容
     * @throws IOException 文件已存在、路径不安全或写入失败
     */
    public static void writeNew(Path root, Path file, byte[] bytes) throws IOException {
        withParent(root, file, true, (parent, name) -> {
            try (var channel = parent.newByteChannel(name, Set.of(StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            return null;
        });
    }
    /** Create outside the container mount, then move into the opened target directory. */
    static void createDirectory(Path root, SecureDirectoryStream<Path> parent, Path name) throws IOException {
        Path trustedParent = root.toAbsolutePath().getParent();
        if (trustedParent == null || name.getNameCount() != 1 || name.isAbsolute()
                || name.toString().equals(".") || name.toString().equals("..")) {
            throw new IOException("Invalid member directory name");
        }
        // A container receives only root; its parent is server-owned and cannot be mutated by code.
        // Creating directly under a member path would race ancestor replacement with symlinks.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(trustedParent)) {
            if (!(stream instanceof SecureDirectoryStream<Path> staging)) {
                throw new IOException("Member isolation requires SecureDirectoryStream");
            }
            Path temporary = Files.createTempDirectory(trustedParent, ".member-directory-").getFileName();
            try {
                try { staging.move(temporary, parent, name); }
                catch (FileAlreadyExistsException concurrentCreator) {
                    // The caller opens the winner with NOFOLLOW_LINKS; links remain rejected.
                }
            } finally {
                try { staging.deleteDirectory(temporary); } catch (NoSuchFileException moved) { }
            }
        }
    }

    private interface Operation<T> { T run(SecureDirectoryStream<Path> parent, Path name) throws IOException; }
    private static <T> T withParent(Path root, Path file, boolean create, Operation<T> operation) throws IOException {
        Path path = MemberFileIsolation.validate(root, file.toString());
        if (path.equals(root)) throw new IOException("Expected a member file, not the member root");
        List<DirectoryStream<Path>> handles = new ArrayList<>();
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(root);
            handles.add(stream);
            if (!(stream instanceof SecureDirectoryStream<Path> current)) {
                throw new IOException("Member isolation requires a filesystem supporting SecureDirectoryStream (Linux deployment)");
            }
            Path parent = root.relativize(path.getParent());
            for (Path segment : parent) {
                if (segment.toString().isEmpty()) continue;
                SecureDirectoryStream<Path> next;
                try {
                    next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missing) {
                    if (!create) throw missing;
                    createDirectory(root, current, segment);
                    next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                }
                handles.add(next); current = next;
            }
            return operation.run(current, path.getFileName());
        } finally {
            for (int i=handles.size()-1; i>=0; i--) handles.get(i).close();
        }
    }
}
