package cncode.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class CommandRegistry {
    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();

    public CommandRegistry() {
        registerDefaults();
    }

    public void register(Command command, Function<CommandContext, String> handler) {
        commands.add(command);
        if (handler == null) {
            return;
        }
        handlers.put(key(command.name()), handler);
        for (String alias : command.aliases()) {
            handlers.put(key(alias), handler);
        }
    }

    public Optional<Command> find(String name) {
        return commands.stream().filter(command -> command.matches(name)).findFirst();
    }

    public List<Command> search(String prefix) {
        return commands.stream()
                .filter(command -> !command.hidden())
                .filter(command -> command.startsWith(prefix))
                .sorted(Comparator.comparing(Command::name))
                .toList();
    }

    public String execute(String name, CommandContext context) {
        Function<CommandContext, String> handler = handlers.get(key(name));
        if (handler != null) {
            return safe(handler.apply(context));
        }
        Optional<Command> command = find(name);
        if (command.isEmpty()) {
            return "Unknown command: " + name;
        }
        handler = handlers.get(key(command.get().name()));
        if (handler == null) {
            return "No handler registered for /" + command.get().name();
        }
        return safe(handler.apply(context));
    }

    public List<Command> listAll() {
        return List.copyOf(commands);
    }

    public List<Command> listVisible() {
        return commands.stream()
                .filter(command -> !command.hidden())
                .sorted(Comparator.comparing(Command::name))
                .toList();
    }

    private void registerDefaults() {
        register(new Command("help", "Show slash commands", new String[]{"h", "?"}, Command.CommandType.LOCAL, false), this::help);
        register(new Command("compact", "Compact current conversation", new String[]{"c"}, Command.CommandType.LOCAL_UI, false), null);
        register(new Command("clear", "Clear current chat view and history", new String[0], Command.CommandType.LOCAL_UI, false), null);
        register(new Command("plan", "Enter plan-only mode", new String[]{"p"}, Command.CommandType.LOCAL_UI, false), null);
        register(new Command("do", "Return to execution mode", new String[0], Command.CommandType.LOCAL_UI, false), null);
        register(new Command("session", "Manage chat sessions", new String[0], Command.CommandType.LOCAL, false), this::session);
        register(new Command("memory", "Manage memories", new String[0], Command.CommandType.LOCAL, false), this::memory);
        register(new Command("permission", "Show permission mode", new String[]{"perm"}, Command.CommandType.LOCAL, false), this::permission);
        register(new Command("resume", "Resume a saved session", new String[]{"r"}, Command.CommandType.LOCAL_UI, false), null);
        register(new Command("skills", "List installed skills", new String[0], Command.CommandType.LOCAL, false), this::skills);
        register(new Command("status", "Show runtime status", new String[]{"s"}, Command.CommandType.LOCAL, false), this::status);
        register(new Command("review", "Inject a code review prompt", new String[0], Command.CommandType.PROMPT, false), this::review);
    }

    private String help(CommandContext context) {
        String target = context.args().strip();
        if (!target.isBlank()) {
            return find(target)
                    .map(command -> "/" + command.name() + aliases(command) + "\n" + command.description())
                    .orElse("Unknown command: " + target);
        }
        StringBuilder builder = new StringBuilder("Available commands:\n");
        for (Command command : listVisible()) {
            builder.append("/")
                    .append(command.name())
                    .append(aliases(command))
                    .append(" - ")
                    .append(command.description())
                    .append("\n");
        }
        builder.append("\nType /help <command> for details.");
        return builder.toString();
    }

    private String status(CommandContext context) {
        int[] tokens = context.tokenCount().get();
        return """
                Mode: %s
                Tokens: %d input / %d output
                Tools: %d
                Memories: %d
                Model: %s
                Directory: %s
                """.formatted(
                context.permissionMode().get(),
                tokens.length > 0 ? tokens[0] : 0,
                tokens.length > 1 ? tokens[1] : 0,
                context.toolCount().getAsInt(),
                context.memoryList().get().size(),
                context.model().get(),
                context.workDir()
        ).strip();
    }

    private String memory(CommandContext context) {
        String args = context.args().strip();
        if (args.isBlank() || "list".equalsIgnoreCase(args)) {
            List<String> memories = context.memoryList().get();
            return memories.isEmpty() ? "No memories stored yet." : String.join("\n", memories);
        }
        if ("clear".equalsIgnoreCase(args)) {
            context.memoryClear().run();
            return "All auto-memories cleared.";
        }
        return "Usage: /memory [list|clear]";
    }

    private String session(CommandContext context) {
        String args = context.args().strip();
        if (args.isBlank() || "info".equalsIgnoreCase(args) || "list".equalsIgnoreCase(args)) {
            return context.sessionInfo().get();
        }
        return "Usage: /session [list|info]";
    }

    private String permission(CommandContext context) {
        String args = context.args().strip();
        if (args.isBlank() || "info".equalsIgnoreCase(args)) {
            return "Current permission mode: " + context.permissionMode().get();
        }
        if (args.startsWith("mode")) {
            return "Usage: /permission mode <default|acceptEdits|plan|bypassPermissions>";
        }
        return "Usage: /permission [info|mode <mode>|rules]";
    }

    private String skills(CommandContext context) {
        List<String> skills = context.skillList().get();
        if (skills.isEmpty()) {
            return "No skills installed.\n\nAdd skills to .cncode/skills/<skill-name>/SKILL.md";
        }
        return String.join("\n", skills);
    }

    private String review(CommandContext context) {
        String focus = context.args().strip();
        return """
                Review current git diff. Focus on:
                - Logic errors
                - Security issues
                - Performance problems
                - Code style
                %s
                """.formatted(focus.isBlank() ? "" : "\nAdditional focus: " + focus).strip();
    }

    private String aliases(Command command) {
        if (command.aliases().length == 0) {
            return "";
        }
        return " (" + String.join(", ", command.aliases()) + ")";
    }

    private String key(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.startsWith("/")) {
            safe = safe.substring(1);
        }
        return safe.toLowerCase();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
