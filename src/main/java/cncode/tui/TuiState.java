package cncode.tui;

import java.util.ArrayList;
import java.util.List;

public class TuiState {
    private final List<TuiMessage> messages = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private int cursorIndex = 0;
    private int scrollOffset = 0;
    private int horizontalOffset = 0;
    private boolean busy = false;
    private boolean running = true;

    public synchronized List<TuiMessage> messagesSnapshot() {
        return new ArrayList<>(messages);
    }

    public synchronized void addMessage(TuiMessageRole role, String content) {
        messages.add(new TuiMessage(role, content));
    }

    public synchronized TuiMessage addAssistantPlaceholder() {
        TuiMessage message = new TuiMessage(TuiMessageRole.ASSISTANT, "");
        messages.add(message);
        return message;
    }

    public synchronized void appendTo(TuiMessage message, String text) {
        message.append(text);
    }

    public synchronized String input() {
        return input.toString();
    }

    public synchronized int cursorIndex() {
        return cursorIndex;
    }

    public synchronized void insertCharacter(char character) {
        input.insert(cursorIndex, character);
        cursorIndex++;
    }

    public synchronized void insertNewline() {
        insertCharacter('\n');
    }

    public synchronized void backspace() {
        if (cursorIndex <= 0) {
            return;
        }
        input.deleteCharAt(cursorIndex - 1);
        cursorIndex--;
    }

    public synchronized void delete() {
        if (cursorIndex >= input.length()) {
            return;
        }
        input.deleteCharAt(cursorIndex);
    }

    public synchronized void moveCursorLeft() {
        if (cursorIndex > 0) {
            cursorIndex--;
        }
    }

    public synchronized void moveCursorRight() {
        if (cursorIndex < input.length()) {
            cursorIndex++;
        }
    }

    public synchronized String consumeInput() {
        String value = input.toString();
        input.setLength(0);
        cursorIndex = 0;
        return value;
    }

    public synchronized int scrollOffset() {
        return scrollOffset;
    }

    public synchronized void scrollUp() {
        scrollOffset += 3;
    }

    public synchronized void scrollDown() {
        scrollOffset = Math.max(0, scrollOffset - 3);
    }

    public synchronized void scrollToBottom() {
        scrollOffset = 0;
    }

    public synchronized int horizontalOffset() {
        return horizontalOffset;
    }

    public synchronized void moveTextLeft() {
        horizontalOffset = Math.max(0, horizontalOffset - 2);
    }

    public synchronized void moveTextRight(int maxOffset) {
        horizontalOffset = Math.min(Math.max(0, maxOffset), horizontalOffset + 2);
    }

    public synchronized boolean busy() {
        return busy;
    }

    public synchronized void setBusy(boolean busy) {
        this.busy = busy;
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized void stop() {
        running = false;
    }
}
