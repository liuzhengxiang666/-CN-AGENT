package cncode.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillParser {
    public SkillDefinition parseDirectory(Path dir, SkillSource.Tier tier, boolean loadBody) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        Path yaml = root.resolve("skill.yaml");
        Path prompt = root.resolve("prompt.md");
        if (Files.isRegularFile(yaml) && Files.isRegularFile(prompt)) {
            return parseYamlAndPrompt(root, yaml, prompt, tier, loadBody);
        }
        Path skillMd = root.resolve("SKILL.md");
        if (Files.isRegularFile(skillMd)) {
            return parseSkillMd(root, skillMd, tier, loadBody);
        }
        throw new IOException("Skill directory must contain SKILL.md or skill.yaml + prompt.md: " + root);
    }

    public SkillDefinition parseBuiltin(String name, String markdown) {
        ParsedMarkdown parsed = splitFrontmatter(markdown == null ? "" : markdown);
        Map<String, Object> meta = parseYaml(parsed.frontmatter());
        SkillMeta skillMeta = metaFromMap(meta, name, parsed.body());
        return new SkillDefinition(skillMeta, parsed.body().strip(), new SkillSource(SkillSource.Tier.BUILTIN, null), true, List.of(), List.of());
    }

    private SkillDefinition parseYamlAndPrompt(Path root, Path yaml, Path prompt, SkillSource.Tier tier, boolean loadBody) throws IOException {
        Map<String, Object> meta = parseYaml(Files.readString(yaml, StandardCharsets.UTF_8));
        String body = loadBody ? Files.readString(prompt, StandardCharsets.UTF_8).strip() : "";
        SkillMeta skillMeta = metaFromMap(meta, root.getFileName().toString(), loadBody ? body : "");
        return new SkillDefinition(skillMeta, body, new SkillSource(tier, root), loadBody, findToolSchemas(root), findToolScripts(root));
    }

    private SkillDefinition parseSkillMd(Path root, Path skillMd, SkillSource.Tier tier, boolean loadBody) throws IOException {
        String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
        ParsedMarkdown parsed = splitFrontmatter(raw);
        Map<String, Object> meta;
        try {
            meta = parseYaml(parsed.frontmatter());
        } catch (RuntimeException error) {
            meta = Map.of();
        }
        String body = loadBody ? parsed.body().strip() : "";
        SkillMeta skillMeta = metaFromMap(meta, root.getFileName().toString(), parsed.body());
        return new SkillDefinition(skillMeta, body, new SkillSource(tier, root), loadBody, findToolSchemas(root), findToolScripts(root));
    }

    private ParsedMarkdown splitFrontmatter(String raw) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParsedMarkdown("", normalized);
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return new ParsedMarkdown("", normalized);
        }
        int bodyStart = normalized.indexOf('\n', end + 1);
        if (bodyStart < 0) {
            bodyStart = normalized.length();
        }
        return new ParsedMarkdown(normalized.substring(4, end), normalized.substring(Math.min(bodyStart + 1, normalized.length())));
    }

    private Map<String, Object> parseYaml(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) {
            return result;
        }
        String currentListKey = null;
        List<String> currentList = null;
        for (String rawLine : yaml.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ") && currentListKey != null) {
                currentList.add(unquote(line.substring(2).strip()));
                result.put(currentListKey, currentList);
                continue;
            }
            currentListKey = null;
            currentList = null;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.isBlank()) {
                currentListKey = key;
                currentList = new ArrayList<>();
                result.put(key, currentList);
            } else if (value.startsWith("[") && value.endsWith("]")) {
                result.put(key, parseInlineList(value));
            } else {
                result.put(key, unquote(value));
            }
        }
        return result;
    }

    private List<String> parseInlineList(String value) {
        String inner = value.substring(1, value.length() - 1).strip();
        if (inner.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : inner.split(",")) {
            String item = unquote(part.strip());
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    private SkillMeta metaFromMap(Map<String, Object> meta, String fallbackName, String bodyForDescription) {
        String name = string(meta, "name");
        if (name.isBlank()) {
            name = fallbackName;
        }
        String description = firstNonBlank(string(meta, "description"), firstBodyLine(bodyForDescription));
        String context = string(meta, "context");
        return new SkillMeta(
                name,
                description,
                firstNonBlank(string(meta, "whenToUse"), string(meta, "when_to_use")),
                list(meta, "tags"),
                firstList(meta, "allowedTools", "allowed_tools", "tools"),
                SkillExecutionMode.parse(firstNonBlank(string(meta, "mode"), context)),
                string(meta, "model"),
                ForkContextMode.parse(firstNonBlank(string(meta, "forkContext"), string(meta, "fork_context")))
        );
    }

    private List<Path> findToolSchemas(Path root) throws IOException {
        Path tools = root.resolve("tools").normalize();
        if (!Files.isDirectory(tools) || !tools.startsWith(root)) {
            return List.of();
        }
        try (var stream = Files.walk(tools, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .map(Path::normalize)
                    .filter(path -> path.startsWith(root))
                    .toList();
        }
    }

    private List<Path> findToolScripts(Path root) throws IOException {
        Path tools = root.resolve("tools").normalize();
        if (!Files.isDirectory(tools) || !tools.startsWith(root)) {
            return List.of();
        }
        try (var stream = Files.walk(tools, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".schema.json"))
                    .map(Path::normalize)
                    .filter(path -> path.startsWith(root))
                    .toList();
        }
    }

    private String firstBodyLine(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isBlank() && !trimmed.startsWith("#")) {
                return trimmed;
            }
        }
        return "";
    }

    private String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text.strip() : "";
    }

    private List<String> firstList(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            List<String> values = list(map, key);
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    private List<String> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> raw) {
            return raw.stream().map(String::valueOf).map(String::strip).filter(item -> !item.isBlank()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.strip());
        }
        return List.of();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second.strip()) : first.strip();
    }

    private String unquote(String value) {
        String safe = value == null ? "" : value.strip();
        if ((safe.startsWith("\"") && safe.endsWith("\"")) || (safe.startsWith("'") && safe.endsWith("'"))) {
            return safe.substring(1, safe.length() - 1);
        }
        return safe;
    }

    private record ParsedMarkdown(String frontmatter, String body) {
    }
}
