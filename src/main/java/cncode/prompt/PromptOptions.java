package cncode.prompt;

public record PromptOptions(String projectInstructions, String toolRules, String skillSummary, String memorySummary) {
    public PromptOptions {
        projectInstructions = projectInstructions == null ? "" : projectInstructions;
        toolRules = toolRules == null ? "" : toolRules;
        skillSummary = skillSummary == null ? "" : skillSummary;
        memorySummary = memorySummary == null ? "" : memorySummary;
    }
}
