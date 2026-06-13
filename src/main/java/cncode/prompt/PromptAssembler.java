package cncode.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PromptAssembler {
    private final List<PromptSection> sections = new ArrayList<>();

    public PromptAssembler add(PromptSection section) {
        if (section != null) {
            sections.add(section);
        }
        return this;
    }

    public String build() {
        return sections.stream()
                .sorted(Comparator.comparingInt(PromptSection::priority).thenComparing(PromptSection::name))
                .map(PromptSection::content)
                .map(String::strip)
                .filter(content -> !content.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    public static String buildStablePrompt(PromptOptions options) {
        PromptOptions safeOptions = options == null ? new PromptOptions("", "", "", "") : options;
        return new PromptAssembler()
                .add(PromptSections.identity())
                .add(PromptSections.behavior())
                .add(PromptSections.toolUse())
                .add(PromptSections.codeStyle())
                .add(PromptSections.safety())
                .add(PromptSections.taskModes())
                .add(PromptSections.contextManagement())
                .add(PromptSections.outputStyle())
                .add(PromptSections.projectInstructions(safeOptions.projectInstructions()))
                .add(PromptSections.toolRules(safeOptions.toolRules()))
                .add(PromptSections.skills(safeOptions.skillSummary()))
                .add(PromptSections.memory(safeOptions.memorySummary()))
                .build();
    }
}
