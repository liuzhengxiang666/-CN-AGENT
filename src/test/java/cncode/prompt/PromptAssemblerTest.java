package cncode.prompt;

public class PromptAssemblerTest {
    public static void main(String[] args) {
        sortsSectionsAndFiltersEmptyContent();
        buildsStablePromptWithoutDynamicEnvironment();
        buildsPlanReminderByIteration();
    }

    private static void sortsSectionsAndFiltersEmptyContent() {
        String prompt = new PromptAssembler()
                .add(new PromptSection("late", 20, "late"))
                .add(new PromptSection("empty", 10, " "))
                .add(new PromptSection("early", 0, "early"))
                .build();

        if (!"early\n\nlate".equals(prompt)) {
            throw new AssertionError("prompt order invalid: " + prompt);
        }
    }

    private static void buildsStablePromptWithoutDynamicEnvironment() {
        String prompt = PromptAssembler.buildStablePrompt(new PromptOptions("项目规则", "", "", ""));
        if (!prompt.contains("CN Code") || !prompt.contains("编辑前必须先读") || !prompt.contains("<system-reminder>")) {
            throw new AssertionError("stable prompt missing core rules: " + prompt);
        }
        if (prompt.contains("Git 分支") || prompt.contains("当前时间")) {
            throw new AssertionError("stable prompt should not contain dynamic environment: " + prompt);
        }
    }

    private static void buildsPlanReminderByIteration() {
        String first = PlanModeReminder.build(1);
        String second = PlanModeReminder.build(2);
        String fifth = PlanModeReminder.build(5);
        if (!first.contains("一次只问一个关键问题") || !first.contains("cncode-options")) {
            throw new AssertionError("full plan reminder invalid: " + first);
        }
        if (!fifth.contains("一次只问一个关键问题")) {
            throw new AssertionError("interval plan reminder should be full: " + fifth);
        }
        if (second.length() >= first.length() || !second.contains("当前仍处于 Plan 模式")) {
            throw new AssertionError("sparse plan reminder invalid: " + second);
        }
    }
}
