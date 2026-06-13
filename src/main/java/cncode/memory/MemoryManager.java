package cncode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class MemoryManager {
    public static final String MEMORY_FILE = "memories.md";

    private final Path workDir;
    private final Path file;

    public MemoryManager(Path workDir) {
        this.workDir = (workDir == null ? Path.of("") : workDir).toAbsolutePath().normalize();
        this.file = this.workDir.resolve(MEMORY_FILE).normalize();
    }

    public Path file() {
        return file;
    }

    public String load() {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException ignored) {
            return "";
        }
    }

    public boolean isEmpty() {
        return load().isBlank();
    }

    public void append(String memory) throws IOException {
        String safe = memory == null ? "" : memory.strip();
        if (safe.isBlank()) {
            return;
        }
        String line = "- " + safe.replace("\r", "").replace("\n", "\n  ") + " _(记于 " + Instant.now() + ")_";
        Files.writeString(file, existingPrefix() + line + "\n", StandardCharsets.UTF_8);
    }

    public void appendExtracted(String memory) throws IOException {
        String safe = memory == null ? "" : memory.strip();
        if (safe.isBlank()) {
            return;
        }
        append(safe);
    }

    public void clear() throws IOException {
        Files.writeString(file, "", StandardCharsets.UTF_8);
    }

    public String promptSection() {
        String content = load();
        if (content.isBlank()) {
            return "";
        }
        return "# Auto Memory\n\n" + content;
    }

    private String existingPrefix() throws IOException {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        String existing = Files.readString(file, StandardCharsets.UTF_8);
        if (existing.isBlank()) {
            return "";
        }
        return existing.endsWith("\n") ? existing : existing + "\n";
    }
}
