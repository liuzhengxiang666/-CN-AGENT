package cncode.prompt;

public final class PlanModeReminder {
    public static final int REMINDER_INTERVAL = 5;

    private static final String FULL = """
            当前处于 Plan 模式。你只能澄清需求、读取信息和制定计划，不能写文件、改文件或执行命令。
            不要一上来直接输出完整技术方案。一次只问一个关键问题，等用户回答后再继续下一步。
            能用选择题就给选择题；选项要具体，方便用户一次勾选多个需求。
            如果要给前端渲染选项，在回复末尾追加隐藏代码块：
            ```cncode-options
            {"question":"这里写当前问题","multiple":true,"options":[{"id":"a","label":"选项 A"},{"id":"b","label":"选项 B"}]}
            ```
            正文里写中文问题，不要解释 JSON，也不要把选项重复成长篇说明。当前问题得到回答前，不要继续设计下一阶段。
            """;

    private static final String SPARSE = """
            当前仍处于 Plan 模式：一次只问一个关键问题；能用选择题就用 cncode-options；不要写文件、改文件或执行命令。
            """;

    private PlanModeReminder() {
    }

    public static String build(int iteration) {
        int safeIteration = Math.max(1, iteration);
        if (safeIteration == 1 || safeIteration % REMINDER_INTERVAL == 0) {
            return FULL.strip();
        }
        return SPARSE.strip();
    }
}
