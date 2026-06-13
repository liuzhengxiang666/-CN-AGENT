package cncode.compact;

public record CompactResult(boolean compacted, int beforeTokens, int afterTokens, String message) {
    public static CompactResult skipped(int tokens) {
        return new CompactResult(false, tokens, tokens, "");
    }

    public static CompactResult compacted(int beforeTokens, int afterTokens) {
        return new CompactResult(true, beforeTokens, afterTokens, "Compacted: " + beforeTokens + " -> " + afterTokens + " estimated tokens");
    }
}
