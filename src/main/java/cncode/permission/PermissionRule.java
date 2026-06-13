package cncode.permission;

public record PermissionRule(PermissionDecision decision, String toolName, String pattern, String source) {
    public boolean matches(String tool, String content) {
        boolean toolMatches = toolName == null || toolName.isBlank() || "*".equals(toolName) || toolName.equalsIgnoreCase(tool);
        if (!toolMatches) {
            return false;
        }
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
            return true;
        }
        String haystack = content == null ? "" : content;
        String normalized = pattern.replace("*", "");
        return haystack.contains(normalized);
    }

    public String toLine() {
        return decision.name().toLowerCase() + "|" + toolName + "|" + pattern;
    }
}
