package cncode.permission;

public record PermissionCheckResult(PermissionDecision decision, String reason, PermissionRequest request) {
    public static PermissionCheckResult allow(String reason) {
        return new PermissionCheckResult(PermissionDecision.ALLOW, reason, null);
    }

    public static PermissionCheckResult deny(String reason) {
        return new PermissionCheckResult(PermissionDecision.DENY, reason, null);
    }

    public static PermissionCheckResult ask(PermissionRequest request) {
        return new PermissionCheckResult(PermissionDecision.ASK, request.reason(), request);
    }
}
