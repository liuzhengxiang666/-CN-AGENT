package cncode.command;

public final class CommandParser {
    private CommandParser() {
    }

    public static ParsedCommand parse(String input) {
        String safe = input == null ? "" : input.trim();
        if (!safe.startsWith("/")) {
            return new ParsedCommand(false, "", "");
        }
        String body = safe.substring(1).trim();
        if (body.isBlank()) {
            return new ParsedCommand(true, "", "");
        }
        int space = body.indexOf(' ');
        if (space < 0) {
            return new ParsedCommand(true, body, "");
        }
        return new ParsedCommand(true, body.substring(0, space), body.substring(space + 1).trim());
    }

    public record ParsedCommand(boolean command, String name, String args) {
    }
}
