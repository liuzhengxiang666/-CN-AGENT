package cncode.permission;

import java.nio.file.Files;
import java.nio.file.Path;

public class PathSandbox {
    private final Path root;

    public PathSandbox(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public PermissionCheckResult check(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            return PermissionCheckResult.allow("无路径参数");
        }
        try {
            Path resolved = Path.of(inputPath);
            Path target = (resolved.isAbsolute() ? resolved : root.resolve(resolved)).toAbsolutePath().normalize();
            Path realRoot = Files.exists(root) ? root.toRealPath() : root;
            Path realTarget = Files.exists(target) ? target.toRealPath() : target;
            if (!realTarget.startsWith(realRoot)) {
                return PermissionCheckResult.deny("路径沙箱拒绝访问项目外路径：" + inputPath);
            }
            return PermissionCheckResult.allow("路径位于项目目录内");
        } catch (Exception error) {
            return PermissionCheckResult.deny("路径解析失败，已拒绝：" + error.getMessage());
        }
    }
}
