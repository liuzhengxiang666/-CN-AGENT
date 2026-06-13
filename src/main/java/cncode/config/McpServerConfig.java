package cncode.config;

import java.util.List;
import java.util.Map;

public record McpServerConfig(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        int timeoutSeconds
) {
    public McpServerConfig {
        name = name == null ? "" : name.trim();
        command = command == null ? "" : command.trim();
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        url = url == null ? "" : url.trim();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }

    public boolean stdio() {
        return !command.isBlank();
    }

    public boolean http() {
        return !url.isBlank();
    }
}
