package cncode.tui;

public class TuiStateTest {
    public static void main(String[] args) {
        TuiState state = new TuiState();
        state.insertCharacter('h');
        state.insertCharacter('i');
        state.insertNewline();
        state.insertCharacter('!');

        if (!"hi\n!".equals(state.input())) {
            throw new AssertionError("input state is wrong");
        }

        String consumed = state.consumeInput();
        if (!"hi\n!".equals(consumed) || !state.input().isEmpty()) {
            throw new AssertionError("input consume is wrong");
        }

        state.addMessage(TuiMessageRole.USER, "question");
        TuiMessage assistant = state.addAssistantPlaceholder();
        state.appendTo(assistant, "ans");
        state.appendTo(assistant, "wer");

        if (state.messagesSnapshot().size() != 2) {
            throw new AssertionError("message size is wrong");
        }
        if (!"answer".equals(state.messagesSnapshot().get(1).content())) {
            throw new AssertionError("assistant delta is wrong");
        }

        state.scrollUp();
        if (state.scrollOffset() <= 0) {
            throw new AssertionError("scroll offset did not increase");
        }
        state.scrollToBottom();
        if (state.scrollOffset() != 0) {
            throw new AssertionError("scroll to bottom failed");
        }

        state.moveTextLeft();
        if (state.horizontalOffset() != 0) {
            throw new AssertionError("horizontal offset should not be negative");
        }
        state.moveTextRight(4);
        state.moveTextRight(4);
        state.moveTextRight(4);
        if (state.horizontalOffset() != 4) {
            throw new AssertionError("horizontal offset max bound failed");
        }
        state.moveTextLeft();
        if (state.horizontalOffset() != 2) {
            throw new AssertionError("horizontal offset left move failed");
        }
    }
}
