package cncode.skill;

public class ActiveSkillStateTest {
    public static void main(String[] args) {
        ActiveSkillState state = new ActiveSkillState();
        if (!state.buildContext().isBlank()) {
            throw new AssertionError("empty state should not render");
        }
        state.activate("one", "Body one");
        state.activate("two", "Body two");
        String context = state.buildContext();
        if (!context.contains("## Active Skills") || !context.contains("one") || !context.contains("Body two")) {
            throw new AssertionError("active context invalid: " + context);
        }
        state.clear();
        if (!state.names().isEmpty() || !state.buildContext().isBlank()) {
            throw new AssertionError("clear failed");
        }
    }
}
