package cncode.tui;

import cncode.config.AppConfig;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TuiRenderer {
    private static final int TITLE_HEIGHT = 2;
    private static final int INPUT_HEIGHT = 4;
    private static final int STATUS_HEIGHT = 1;
    private static final int MIN_HEIGHT = TITLE_HEIGHT + INPUT_HEIGHT + STATUS_HEIGHT + 4;

    public void render(Screen screen, TuiState state, AppConfig config) throws IOException {
        TerminalSize size = screen.getTerminalSize();
        if (size.getRows() < MIN_HEIGHT || size.getColumns() < 30) {
            throw new IOException("终端窗口太小，无法启动 TUI。");
        }

        screen.clear();
        TextGraphics graphics = screen.newTextGraphics();
        int width = size.getColumns();
        int height = size.getRows();
        int statusY = height - 1;
        int inputTop = height - STATUS_HEIGHT - INPUT_HEIGHT;
        int historyTop = TITLE_HEIGHT;
        int historyHeight = inputTop - historyTop;

        renderTitle(graphics, config, width);
        renderHistory(graphics, state, width, historyTop, historyHeight);
        renderInput(graphics, state, width, inputTop);
        renderStatus(graphics, config, width, statusY);
        placeCursor(screen, state, width, inputTop);
        screen.refresh();
    }

    private void renderTitle(TextGraphics graphics, AppConfig config, int width) {
        graphics.setBackgroundColor(TextColor.ANSI.BLACK);
        graphics.setForegroundColor(TextColor.ANSI.CYAN);
        graphics.enableModifiers(SGR.BOLD);
        graphics.putString(0, 0, padRight(" CN Code", width));
        graphics.disableModifiers(SGR.BOLD);
        graphics.setForegroundColor(TextColor.ANSI.WHITE);
        graphics.putString(0, 1, padRight(" " + line(width - 2, "─"), width));
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private void renderHistory(TextGraphics graphics, TuiState state, int width, int top, int height) {
        int offset = state.horizontalOffset();
        int contentWidth = Math.max(12, width - offset);
        List<RenderLine> lines = flattenMessages(state.messagesSnapshot(), contentWidth);
        int maxStart = Math.max(0, lines.size() - height);
        int start = Math.max(0, maxStart - state.scrollOffset());
        int end = Math.min(lines.size(), start + height);
        int y = top;

        for (int i = start; i < end; i++) {
            RenderLine line = lines.get(i);
            graphics.setForegroundColor(colorFor(line.role()));
            if (line.bold()) {
                graphics.enableModifiers(SGR.BOLD);
            } else {
                graphics.disableModifiers(SGR.BOLD);
            }
            graphics.putString(0, y++, padRight(indent(line.text(), offset), width));
        }
        graphics.disableModifiers(SGR.BOLD);
    }

    private List<RenderLine> flattenMessages(List<TuiMessage> messages, int width) {
        List<RenderLine> lines = new ArrayList<>();
        if (messages.isEmpty()) {
            lines.add(new RenderLine(TuiMessageRole.SYSTEM, "• 系统  欢迎使用 CN Code。Enter 发送，Ctrl+Enter 换行。", false));
            return lines;
        }
        for (TuiMessage message : messages) {
            String prefix = switch (message.role()) {
                case USER -> "› 你 ";
                case ASSISTANT -> "● AI ";
                case SYSTEM -> "• 系统 ";
            };
            int wrapWidth = Math.max(8, width - prefix.length());
            List<String> wrapped = wrap(message.content().isEmpty() ? " " : message.content(), wrapWidth);
            for (int i = 0; i < wrapped.size(); i++) {
                String linePrefix = i == 0 ? prefix : " ".repeat(prefix.length());
                lines.add(new RenderLine(message.role(), linePrefix + wrapped.get(i), i == 0));
            }
            lines.add(new RenderLine(TuiMessageRole.SYSTEM, "", false));
        }
        return lines;
    }

    private List<String> wrap(String text, int width) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\\R", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            int index = 0;
            while (index < paragraph.length()) {
                int end = Math.min(paragraph.length(), index + width);
                result.add(paragraph.substring(index, end));
                index = end;
            }
        }
        return result;
    }

    private void renderInput(TextGraphics graphics, TuiState state, int width, int inputTop) {
        int offset = state.horizontalOffset();
        int contentWidth = Math.max(10, width - offset);

        graphics.setForegroundColor(TextColor.ANSI.WHITE);
        graphics.putString(0, inputTop, padRight(" " + line(width - 2, "─"), width));

        graphics.setForegroundColor(TextColor.ANSI.YELLOW);
        graphics.putString(0, inputTop + 1, padRight(indent("Enter 发送 | Ctrl+Enter 换行 | Ctrl+←/→ 调整位置", offset), width));

        graphics.setForegroundColor(TextColor.ANSI.CYAN);
        String[] lines = state.input().split("\\R", -1);
        for (int i = 0; i < INPUT_HEIGHT - 2; i++) {
            String prefix = i == 0 ? "› " : "  ";
            String value = i < lines.length ? lines[i] : "";
            graphics.putString(0, inputTop + 2 + i, padRight(indent(prefix + trimToWidth(value, contentWidth - prefix.length()), offset), width));
        }
    }

    private void renderStatus(TextGraphics graphics, AppConfig config, int width, int statusY) {
        graphics.setBackgroundColor(TextColor.ANSI.WHITE);
        graphics.setForegroundColor(TextColor.ANSI.BLACK);
        String status = " default    " + config.protocol() + "/" + config.model();
        graphics.putString(0, statusY, padRight(status, width));
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private void placeCursor(Screen screen, TuiState state, int width, int inputTop) {
        String beforeCursor = state.input().substring(0, Math.min(state.cursorIndex(), state.input().length()));
        String[] lines = beforeCursor.split("\\R", -1);
        int cursorRow = Math.min(lines.length - 1, INPUT_HEIGHT - 3);
        int cursorColumn = Math.min(width - 1, state.horizontalOffset() + 2 + lines[cursorRow].length());
        screen.setCursorPosition(new TerminalPosition(cursorColumn, inputTop + 2 + cursorRow));
    }

    private TextColor colorFor(TuiMessageRole role) {
        return switch (role) {
            case USER -> TextColor.ANSI.CYAN;
            case ASSISTANT -> TextColor.ANSI.WHITE;
            case SYSTEM -> TextColor.ANSI.YELLOW;
        };
    }

    private String indent(String text, int offset) {
        return " ".repeat(Math.max(0, offset)) + text;
    }

    private String padRight(String text, int width) {
        String trimmed = trimToWidth(text, width);
        return trimmed + " ".repeat(Math.max(0, width - trimmed.length()));
    }

    private String trimToWidth(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        return text.substring(0, width);
    }

    private String line(int width, String character) {
        return character.repeat(Math.max(0, width));
    }

    private record RenderLine(TuiMessageRole role, String text, boolean bold) {
    }
}
