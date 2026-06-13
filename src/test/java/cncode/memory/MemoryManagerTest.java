package cncode.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MemoryManagerTest {
    public static void main(String[] args) throws Exception {
        missingFileLoadsEmpty();
        appendsAndClearsMarkdown();
    }

    private static void missingFileLoadsEmpty() throws Exception {
        MemoryManager manager = new MemoryManager(Files.createTempDirectory("cncode-memory"));
        if (!manager.load().isBlank() || !manager.promptSection().isBlank()) {
            throw new AssertionError("missing memory should load empty");
        }
    }

    private static void appendsAndClearsMarkdown() throws Exception {
        Path root = Files.createTempDirectory("cncode-memory-write");
        MemoryManager manager = new MemoryManager(root);
        manager.append("我喜欢中文简洁回答");
        String content = Files.readString(root.resolve("memories.md"), StandardCharsets.UTF_8);
        if (!content.contains("- 我喜欢中文简洁回答") || !manager.promptSection().contains("# Auto Memory")) {
            throw new AssertionError("memory markdown missing: " + content);
        }
        manager.clear();
        if (!manager.load().isBlank()) {
            throw new AssertionError("memory should be cleared");
        }
    }
}
