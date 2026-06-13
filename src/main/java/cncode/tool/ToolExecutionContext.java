package cncode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public record ToolExecutionContext(Path workspaceRoot, Duration timeout, int maxOutputChars) {
    public ToolExecutionContext {
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        maxOutputChars = maxOutputChars <= 0 ? 12000 : maxOutputChars;
    }

    public static ToolExecutionContext defaultContext() {
        return new ToolExecutionContext(Path.of("").toAbsolutePath(), Duration.ofSeconds(10), 12000);
    }

    public Path resolveInsideWorkspace(String userPath) throws IOException {
        if (userPath == null || userPath.isBlank()) {
            throw new IOException("路径不能为空");
        }
        Path resolved = workspaceRoot.resolve(userPath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IOException("路径越界，禁止访问工作目录之外：" + userPath);
        }
        return resolved;
    }

    public String relative(Path path) {
        return workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString();
    }

    public String limit(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxOutputChars) {
            return text;
        }
        return text.substring(0, maxOutputChars) + "\n\n[输出已截断，原始长度：" + text.length() + "]";
    }

    public boolean isTextFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            String type = Files.probeContentType(path);
            return type == null || type.startsWith("text") || type.contains("json") || type.contains("xml");
        } catch (IOException error) {
            return false;
        }
    }
}
