package cncode.prompt;

public record PromptSection(String name, int priority, String content) {
    public PromptSection {
        name = name == null ? "" : name;
        content = content == null ? "" : content;
    }
}
