package cncode.permission;

import cncode.provider.JsonUtil;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public record PermissionRequest(
        String id,
        String toolName,
        String category,
        String argumentSummary,
        String path,
        String reason,
        CompletableFuture<PermissionResponse> response
) {
    public String toJson() {
        return "{"
                + "\"id\":" + JsonUtil.quote(id) + ","
                + "\"toolName\":" + JsonUtil.quote(toolName) + ","
                + "\"category\":" + JsonUtil.quote(category) + ","
                + "\"argumentSummary\":" + JsonUtil.quote(argumentSummary) + ","
                + "\"path\":" + JsonUtil.quote(path) + ","
                + "\"reason\":" + JsonUtil.quote(reason)
                + "}";
    }

    public PermissionResponse await(Duration timeout) {
        try {
            return response.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return PermissionResponse.deny();
        }
    }
}
