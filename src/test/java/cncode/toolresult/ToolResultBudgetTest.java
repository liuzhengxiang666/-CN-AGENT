package cncode.toolresult;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ToolResultBudgetTest {
    public static void main(String[] args) throws Exception {
        spillsLargeToolResultWithoutMutatingOriginal();
        aggregateBudgetPicksLargeFreshResult();
        recordsRoundtrip();
    }

    private static void spillsLargeToolResultWithoutMutatingOriginal() throws Exception {
        Path dir = Files.createTempDirectory("cncode-budget-test");
        String large = "x".repeat(ToolResultBudget.SINGLE_RESULT_LIMIT + 10);
        String content = "<tool-result name=\"read_file\" success=\"true\">\n" + large + "\n</tool-result>";
        List<ChatMessage> messages = List.of(new ChatMessage(ChatRole.USER, content));
        ContentReplacementState state = new ContentReplacementState();

        ApplyResult result = ToolResultBudget.apply(messages, dir, state);

        if (!messages.getFirst().content().equals(content)) {
            throw new AssertionError("original message should not be mutated");
        }
        String api = result.apiMessages().getFirst().content();
        if (!api.contains("[Result of ") || !api.contains(" chars saved to ") || !api.contains("read with read_file if needed") || !api.contains("Preview:")) {
            throw new AssertionError("large tool result was not replaced with a recoverable preview: " + api);
        }
        Path spillDir = dir.resolve(ToolResultBudget.SPILL_SUBDIR);
        Path file = Files.list(spillDir).findFirst().orElseThrow(() -> new AssertionError("spill file missing"));
        if (!file.toAbsolutePath().toString().endsWith(".txt")) {
            throw new AssertionError("spill file should be a .txt file: " + file);
        }
        if (!api.contains(file.toAbsolutePath().normalize().toString())) {
            throw new AssertionError("preview should include absolute spill path: " + api);
        }
        String written = Files.readString(file, StandardCharsets.UTF_8);
        if (!large.equals(written)) {
            throw new AssertionError("spill file content differs from original tool result body");
        }
        String preview = api.substring(api.indexOf("Preview:\n") + "Preview:\n".length());
        if (!preview.contains("... (preview truncated)") || preview.length() > ToolResultBudget.PREVIEW_CHARS + 80) {
            throw new AssertionError("preview should be bounded and include truncation marker: " + preview.length());
        }
        ApplyResult second = ToolResultBudget.apply(messages, dir, state);
        if (!api.equals(second.apiMessages().getFirst().content())) {
            throw new AssertionError("repeated replacement must be stable");
        }
    }

    private static void aggregateBudgetPicksLargeFreshResult() throws Exception {
        Path dir = Files.createTempDirectory("cncode-budget-aggregate-test");
        String smaller = "a".repeat(5_500);
        String larger = "b".repeat(7_500);
        String content = "<tool-result name=\"one\" success=\"true\">\n" + smaller + "\n</tool-result>\n"
                + "<tool-result name=\"two\" success=\"true\">\n" + larger + "\n</tool-result>";

        ApplyResult result = ToolResultBudget.apply(
                List.of(new ChatMessage(ChatRole.USER, content)),
                dir,
                new ContentReplacementState()
        );

        String api = result.apiMessages().getFirst().content();
        if (!api.contains("[Result of ") || api.length() >= content.length()) {
            throw new AssertionError("aggregate over limit did not compress a large tool result");
        }
        if (!api.contains("a".repeat(1000)) || !api.contains("b".repeat(100))) {
            throw new AssertionError("aggregate replacement should target the larger result first");
        }
        String spilled = Files.readString(Files.list(dir.resolve(ToolResultBudget.SPILL_SUBDIR)).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        if (!larger.equals(spilled)) {
            throw new AssertionError("aggregate replacement should spill the larger result first");
        }
    }

    private static void recordsRoundtrip() throws Exception {
        Path dir = Files.createTempDirectory("cncode-records-test");
        List<ContentReplacementRecord> records = List.of(ContentReplacementRecord.toolResult("id-1", "replacement\nvalue"));
        ReplacementRecordsIO.append(dir, records);
        List<ContentReplacementRecord> loaded = ReplacementRecordsIO.load(dir);
        if (loaded.size() != 1 || !"id-1".equals(loaded.getFirst().toolUseId()) || !loaded.getFirst().replacement().contains("value")) {
            throw new AssertionError("replacement records roundtrip failed: " + loaded);
        }
        if (!ReplacementRecordsIO.load(dir.resolve("missing")).isEmpty()) {
            throw new AssertionError("missing records file should return empty list");
        }
    }
}
