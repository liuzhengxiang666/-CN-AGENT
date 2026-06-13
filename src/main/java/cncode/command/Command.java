package cncode.command;

import java.util.Arrays;

public record Command(String name, String description, String[] aliases, CommandType type, boolean hidden) {
    public Command {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("command name cannot be blank");
        }
        description = description == null ? "" : description;
        aliases = aliases == null ? new String[0] : aliases.clone();
        type = type == null ? CommandType.LOCAL : type;
    }

    public boolean matches(String input) {
        String safe = normalize(input);
        return normalize(name).equals(safe)
                || Arrays.stream(aliases).map(Command::normalize).anyMatch(safe::equals);
    }

    public boolean startsWith(String prefix) {
        String safe = normalize(prefix);
        return normalize(name).startsWith(safe)
                || Arrays.stream(aliases).map(Command::normalize).anyMatch(alias -> alias.startsWith(safe));
    }

    private static String normalize(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.startsWith("/")) {
            safe = safe.substring(1);
        }
        return safe.toLowerCase();
    }

    public enum CommandType {
        LOCAL,
        LOCAL_UI,
        PROMPT
    }
}
