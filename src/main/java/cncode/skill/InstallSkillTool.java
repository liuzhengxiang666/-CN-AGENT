package cncode.skill;

import cncode.tool.Tool;
import cncode.tool.ToolCategory;
import cncode.tool.ToolExecutionContext;
import cncode.tool.ToolJson;
import cncode.tool.ToolMetadata;
import cncode.tool.ToolRegistry;
import cncode.tool.ToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstallSkillTool implements Tool {
    private static final int MAX_SKILL_BYTES = 500_000;
    private static final Pattern NAME_PATTERN = Pattern.compile("(?m)^\\s*name\\s*:\\s*['\"]?([A-Za-z0-9_. -]+)['\"]?\\s*$");

    private final SkillCatalog catalog;
    private final ToolRegistry registry;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public InstallSkillTool(SkillCatalog catalog, ToolRegistry registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                SkillToolPolicy.INSTALL_SKILL_TOOL,
                "Install a CN Code skill from a URL into the project .cncode/skills directory, then reload the skill catalog.",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"URL to a SKILL.md, GitHub skill folder, or skills.sh skill page\"},\"name\":{\"type\":\"string\",\"description\":\"Optional skill name override\"}},\"required\":[\"url\"]}"
        );
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String url = ToolJson.string(arguments, "url").strip();
        String requestedName = ToolJson.string(arguments, "name").strip();
        if (url.isBlank()) {
            return ToolResult.failure(metadata().name(), "Skill URL is required", "Usage: install_skill {\"url\":\"https://.../SKILL.md\"}");
        }
        try {
            URI source = normalizeUrl(url);
            String body = download(source);
            String markdown = extractSkillMarkdown(body, source);
            if (markdown.isBlank()) {
                return ToolResult.failure(metadata().name(), "No SKILL.md found", "Could not find a Markdown skill definition at: " + url);
            }
            String name = SkillMeta.normalizeName(requestedName.isBlank() ? inferName(markdown, source) : requestedName);
            if (name.isBlank()) {
                return ToolResult.failure(metadata().name(), "Skill name not found", "Provide a name argument or include name: in frontmatter.");
            }
            Path skillDir = context.workspaceRoot().resolve(".cncode").resolve("skills").resolve(name).normalize();
            Path skillsRoot = context.workspaceRoot().resolve(".cncode").resolve("skills").normalize();
            if (!skillDir.startsWith(skillsRoot)) {
                return ToolResult.failure(metadata().name(), "Skill path rejected", "Resolved skill path is outside .cncode/skills");
            }
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
            catalog.reload(registry);
            return ToolResult.success(
                    metadata().name(),
                    "Installed skill " + name,
                    "Installed " + name + " skill. You can now use /" + name + " or load_skill."
            );
        } catch (Exception error) {
            return ToolResult.failure(metadata().name(), "Skill install failed", error.getMessage());
        }
    }

    private URI normalizeUrl(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.endsWith("skills.sh")) {
            String[] parts = path.replaceFirst("^/", "").split("/");
            if (parts.length >= 3 && "skills".equals(parts[1])) {
                return URI.create("https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/main/skills/" + parts[2] + "/SKILL.md");
            }
        }
        if ("github.com".equals(host) && path.contains("/blob/")) {
            String rawPath = path.replaceFirst("/blob/", "/");
            return URI.create("https://raw.githubusercontent.com" + rawPath);
        }
        if ("github.com".equals(host) && path.contains("/tree/")) {
            String rawPath = path.replaceFirst("/tree/", "/");
            return URI.create("https://raw.githubusercontent.com" + rawPath + "/SKILL.md");
        }
        if (path.endsWith("/")) {
            return URI.create(url + "SKILL.md");
        }
        if (!path.endsWith(".md") && path.contains("/skills/")) {
            return URI.create(url + (url.endsWith("/") ? "" : "/") + "SKILL.md");
        }
        return uri;
    }

    private String download(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/markdown,text/plain,text/html,*/*")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " while fetching " + uri);
        }
        String body = response.body() == null ? "" : response.body();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_SKILL_BYTES) {
            throw new IOException("Skill file is too large: " + body.length() + " chars");
        }
        return body;
    }

    private String extractSkillMarkdown(String body, URI source) {
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.startsWith("---") || source.getPath().endsWith(".md")) {
            return trimmed;
        }
        int preStart = body.indexOf("<pre");
        if (preStart >= 0) {
            int contentStart = body.indexOf('>', preStart);
            int preEnd = body.indexOf("</pre>", contentStart);
            if (contentStart >= 0 && preEnd > contentStart) {
                return htmlDecode(body.substring(contentStart + 1, preEnd)).strip();
            }
        }
        return "";
    }

    private String inferName(String markdown, URI source) {
        Matcher matcher = NAME_PATTERN.matcher(markdown == null ? "" : markdown);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String path = source.getPath() == null ? "" : source.getPath();
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!part.isBlank() && !"SKILL.md".equalsIgnoreCase(part)) {
                return part;
            }
        }
        return "";
    }

    private String htmlDecode(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
