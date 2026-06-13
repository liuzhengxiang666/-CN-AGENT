package cncode.skill;

public enum SkillExecutionMode {
    INLINE,
    FORK;

    public static SkillExecutionMode parse(String value) {
        if (value == null || value.isBlank()) {
            return INLINE;
        }
        String normalized = value.trim().toLowerCase();
        if ("fork".equals(normalized) || "isolated".equals(normalized)) {
            return FORK;
        }
        return INLINE;
    }
}
