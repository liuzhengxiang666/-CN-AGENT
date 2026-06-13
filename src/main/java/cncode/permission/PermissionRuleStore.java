package cncode.permission;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PermissionRuleStore {
    private final Path userRules;
    private final Path projectRules;

    public PermissionRuleStore(Path projectRoot) {
        String home = System.getProperty("user.home", ".");
        this.userRules = Path.of(home, ".cncode", "permissions.rules");
        this.projectRules = projectRoot.toAbsolutePath().normalize().resolve(".cncode").resolve("permissions.rules");
    }

    public List<PermissionRule> userRules() {
        return readRules(userRules, "user");
    }

    public List<PermissionRule> projectRules() {
        return readRules(projectRules, "project");
    }

    public void appendUserRule(PermissionRule rule) {
        try {
            Files.createDirectories(userRules.getParent());
            Files.writeString(userRules, rule.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                    Files.exists(userRules)
                            ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.APPEND}
                            : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE});
        } catch (Exception ignored) {
        }
    }

    private List<PermissionRule> readRules(Path path, String source) {
        List<PermissionRule> rules = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return rules;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.strip().split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }
                PermissionDecision decision = "allow".equalsIgnoreCase(parts[0])
                        ? PermissionDecision.ALLOW
                        : "deny".equalsIgnoreCase(parts[0]) ? PermissionDecision.DENY : PermissionDecision.ASK;
                rules.add(new PermissionRule(decision, parts[1], parts[2], source));
            }
        } catch (Exception ignored) {
        }
        return rules;
    }
}
