package cncode.skill;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class ActiveSkillState {
    private final Map<String, String> active = new LinkedHashMap<>();
    private final Map<String, List<String>> allowedTools = new LinkedHashMap<>();

    public synchronized void activate(String name, String body) {
        activate(name, body, List.of());
    }

    public synchronized void activate(String name, String body, List<String> tools) {
        String safeName = SkillMeta.normalizeName(name);
        if (safeName.isBlank() || body == null || body.isBlank()) {
            return;
        }
        active.put(safeName, body.strip());
        allowedTools.put(safeName, tools == null ? List.of() : List.copyOf(tools));
    }

    public synchronized void clear() {
        active.clear();
        allowedTools.clear();
    }

    public synchronized List<String> names() {
        return List.copyOf(active.keySet());
    }

    public synchronized String buildContext() {
        if (active.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## Active Skills\n");
        for (Map.Entry<String, String> entry : active.entrySet()) {
            builder.append("\n### ")
                    .append(entry.getKey())
                    .append("\n")
                    .append(entry.getValue())
                    .append("\n");
        }
        return builder.toString().strip();
    }

    public synchronized Predicate<String> toolFilter() {
        Set<String> allowed = new LinkedHashSet<>();
        boolean hasWhitelist = false;
        for (List<String> tools : allowedTools.values()) {
            if (!tools.isEmpty()) {
                hasWhitelist = true;
                allowed.addAll(tools);
            }
        }
        allowed.add(SkillToolPolicy.LOAD_SKILL_TOOL);
        if (!hasWhitelist) {
            return ignored -> true;
        }
        return allowed::contains;
    }
}
