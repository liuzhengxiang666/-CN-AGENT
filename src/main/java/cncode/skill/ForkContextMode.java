package cncode.skill;

public enum ForkContextMode {
    NONE,
    RECENT,
    FULL;

    public static ForkContextMode parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return switch (value.trim().toLowerCase()) {
            case "recent" -> RECENT;
            case "full", "summary", "full_summary" -> FULL;
            default -> NONE;
        };
    }
}
