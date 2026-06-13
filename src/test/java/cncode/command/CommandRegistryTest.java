package cncode.command;

import java.nio.file.Path;
import java.util.List;

public class CommandRegistryTest {
    public static void main(String[] args) {
        findsAliasesAndSearches();
        executesLocalCommands();
        parsesSlashInput();
    }

    private static void findsAliasesAndSearches() {
        CommandRegistry registry = new CommandRegistry();
        if (registry.find("c").isEmpty() || !"compact".equals(registry.find("c").get().name())) {
            throw new AssertionError("compact alias missing");
        }
        if (registry.search("p").stream().noneMatch(command -> "plan".equals(command.name()))) {
            throw new AssertionError("prefix search missing plan");
        }
        if (registry.listVisible().size() != 12) {
            throw new AssertionError("expected ch10 built-in commands: " + registry.listVisible());
        }
        if (registry.find("r").isEmpty() || !"resume".equals(registry.find("r").get().name())) {
            throw new AssertionError("resume alias missing");
        }
    }

    private static void executesLocalCommands() {
        CommandRegistry registry = new CommandRegistry();
        CommandContext context = new CommandContext(
                "",
                Path.of("C:/work"),
                () -> "fake-model",
                () -> "do",
                () -> 7,
                () -> new int[]{1, 2},
                () -> List.of("memory one"),
                () -> {
                },
                () -> "session info",
                List::of
        );
        String help = registry.execute("help", context);
        if (!help.contains("/status") || !help.contains("/memory")) {
            throw new AssertionError("help output missing commands: " + help);
        }
        String status = registry.execute("status", context);
        if (!status.contains("Mode: do") || !status.contains("Tools: 7")) {
            throw new AssertionError("status output invalid: " + status);
        }
        String skills = registry.execute("skills", context);
        if (!skills.contains("No skills installed")) {
            throw new AssertionError("skills output invalid: " + skills);
        }
        if (registry.find("review").isEmpty() || registry.find("review").get().type() != Command.CommandType.PROMPT) {
            throw new AssertionError("review should be a prompt command");
        }
    }

    private static void parsesSlashInput() {
        CommandParser.ParsedCommand parsed = CommandParser.parse("/memory add hello");
        if (!parsed.command() || !"memory".equals(parsed.name()) || !"add hello".equals(parsed.args())) {
            throw new AssertionError("slash parse failed: " + parsed);
        }
        if (CommandParser.parse("hello").command()) {
            throw new AssertionError("plain text should not parse as command");
        }
    }
}
