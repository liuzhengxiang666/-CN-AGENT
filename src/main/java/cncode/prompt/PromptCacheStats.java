package cncode.prompt;

public record PromptCacheStats(long readTokens, long writeTokens, long hitTokens) {
    public static PromptCacheStats empty() {
        return new PromptCacheStats(0, 0, 0);
    }
}
