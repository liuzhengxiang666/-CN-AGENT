package cncode.toolresult;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ToolResultBudget {
    public static final int SINGLE_RESULT_LIMIT = 8_000;
    public static final int MESSAGE_AGGREGATE_LIMIT = 12_000;
    public static final int PREVIEW_CHARS = 1_200;
    public static final String SPILL_SUBDIR = "tool-results";

    private static final String START_TAG = "<tool-result";
    private static final String END_TAG = "</tool-result>";

    private ToolResultBudget() {
    }

    public static ApplyResult apply(List<ChatMessage> messages, Path sessionDir, ContentReplacementState state) {
        ContentReplacementState safeState = state == null ? new ContentReplacementState() : state;
        List<ChatMessage> apiMessages = new ArrayList<>();
        List<ContentReplacementRecord> records = new ArrayList<>();
        Path spillDir = sessionDir.resolve(SPILL_SUBDIR);

        for (ChatMessage message : messages) {
            if (message.role() != ChatRole.USER || !message.content().contains(START_TAG)) {
                apiMessages.add(message);
                continue;
            }
            Replacement replacement = replaceToolResults(message.content(), spillDir, safeState);
            records.addAll(replacement.records());
            apiMessages.add(new ChatMessage(message.role(), replacement.content()));
        }
        return new ApplyResult(apiMessages, records);
    }

    private static Replacement replaceToolResults(String content, Path spillDir, ContentReplacementState state) {
        List<Block> blocks = blocks(content);
        Map<String, String> decisions = new HashMap<>();
        List<ContentReplacementRecord> records = new ArrayList<>();

        for (Block block : blocks) {
            if (state.replacements().containsKey(block.id())) {
                decisions.put(block.id(), state.replacements().get(block.id()));
            } else if (state.seenIds().contains(block.id()) || alreadyReplaced(block.body())) {
                decisions.put(block.id(), block.body());
            } else if (block.body().length() > SINGLE_RESULT_LIMIT) {
                String replacement = spillAndPreview(spillDir, block);
                decisions.put(block.id(), replacement);
                state.seenIds().add(block.id());
                state.replacements().put(block.id(), replacement);
                records.add(ContentReplacementRecord.toolResult(block.id(), replacement));
            }
        }

        int aggregate = blocks.stream()
                .mapToInt(block -> decisions.getOrDefault(block.id(), block.body()).length())
                .sum();
        List<Block> fresh = blocks.stream()
                .filter(block -> !decisions.containsKey(block.id()))
                .sorted(Comparator.comparingInt((Block block) -> block.body().length()).reversed())
                .toList();
        for (Block block : fresh) {
            if (aggregate <= MESSAGE_AGGREGATE_LIMIT) {
                break;
            }
            String replacement = spillAndPreview(spillDir, block);
            aggregate = aggregate - block.body().length() + replacement.length();
            decisions.put(block.id(), replacement);
            state.seenIds().add(block.id());
            state.replacements().put(block.id(), replacement);
            records.add(ContentReplacementRecord.toolResult(block.id(), replacement));
        }

        for (Block block : blocks) {
            if (!decisions.containsKey(block.id())) {
                state.seenIds().add(block.id());
                decisions.put(block.id(), block.body());
            }
        }

        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        for (Block block : blocks) {
            builder.append(content, cursor, block.bodyStart());
            builder.append(decisions.getOrDefault(block.id(), block.body()));
            cursor = block.bodyEnd();
        }
        builder.append(content.substring(cursor));
        return new Replacement(builder.toString(), records);
    }

    private static List<Block> blocks(String content) {
        List<Block> blocks = new ArrayList<>();
        int search = 0;
        while (true) {
            int start = content.indexOf(START_TAG, search);
            if (start < 0) {
                return blocks;
            }
            int tagEnd = content.indexOf(">\n", start);
            if (tagEnd < 0) {
                search = start + START_TAG.length();
                continue;
            }
            int bodyStart = tagEnd + 2;
            int end = content.indexOf("\n" + END_TAG, bodyStart);
            if (end < 0) {
                search = bodyStart;
                continue;
            }
            String body = content.substring(bodyStart, end);
            blocks.add(new Block(stableId(body), body, bodyStart, end));
            search = end + END_TAG.length();
        }
    }

    private static boolean alreadyReplaced(String content) {
        return content.startsWith("[Result of ") || content.startsWith("[Stale output snipped:");
    }

    private static String spillAndPreview(Path spillDir, Block block) {
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(block.id() + ".txt").toAbsolutePath().normalize();
            if (!Files.isRegularFile(file) || Files.size(file) != block.body().getBytes(StandardCharsets.UTF_8).length) {
                Files.writeString(file, block.body(), StandardCharsets.UTF_8);
            }
            String preview = block.body().length() <= PREVIEW_CHARS
                    ? block.body()
                    : block.body().substring(0, PREVIEW_CHARS) + "\n... (preview truncated)";
            return "[Result of " + block.body().length() + " chars saved to " + file
                    + " - read with read_file if needed]\n\nPreview:\n" + preview;
        } catch (IOException ignored) {
            return block.body();
        }
    }

    private static String stableId(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return "tool-result-" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception error) {
            return "tool-result-" + Integer.toHexString(text.hashCode());
        }
    }

    private record Block(String id, String body, int bodyStart, int bodyEnd) {
    }

    private record Replacement(String content, List<ContentReplacementRecord> records) {
    }
}
