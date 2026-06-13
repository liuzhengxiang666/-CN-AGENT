package cncode.permission;

public record PermissionResponse(PermissionScope scope) {
    public static PermissionResponse deny() {
        return new PermissionResponse(PermissionScope.DENY);
    }
}
