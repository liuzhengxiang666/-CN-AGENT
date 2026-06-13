package cncode.web;

import cncode.provider.JsonUtil;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebFileOpener {
    private final Path projectRoot;

    public WebFileOpener(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public OpenFileResponse validate(String inputPath, boolean confirmedExternal) {
        if (inputPath == null || inputPath.isBlank()) {
            return OpenFileResponse.failure(false, "文件路径不能为空。");
        }

        Path target = resolve(inputPath);
        if (!Files.exists(target)) {
            return OpenFileResponse.failure(false, "文件不存在：" + target);
        }

        try {
            Path rootReal = projectRoot.toRealPath();
            Path targetReal = target.toRealPath();
            boolean external = !targetReal.startsWith(rootReal);
            if (external && !confirmedExternal) {
                return OpenFileResponse.failure(true, "项目外文件需要确认后才能打开：" + targetReal);
            }
            return OpenFileResponse.success(external, "文件校验通过：" + targetReal);
        } catch (IOException error) {
            return OpenFileResponse.failure(false, "解析文件真实路径失败：" + error.getMessage());
        }
    }

    public OpenFileResponse open(String inputPath, boolean confirmedExternal) {
        OpenFileResponse validation = validate(inputPath, confirmedExternal);
        if (!validation.success()) {
            return validation;
        }

        Path target = resolve(inputPath);
        if (!Desktop.isDesktopSupported()) {
            return OpenFileResponse.failure(validation.external(), "当前环境不支持系统默认程序打开文件。");
        }
        try {
            Desktop.getDesktop().open(target.toFile());
            return OpenFileResponse.success(validation.external(), "已打开文件：" + target);
        } catch (IOException | UnsupportedOperationException error) {
            return OpenFileResponse.failure(validation.external(), "打开文件失败：" + error.getMessage());
        }
    }

    private Path resolve(String inputPath) {
        Path input = Path.of(inputPath);
        if (input.isAbsolute()) {
            return input.toAbsolutePath().normalize();
        }
        return projectRoot.resolve(input).toAbsolutePath().normalize();
    }

    public record OpenFileResponse(boolean success, boolean external, String message) {
        static OpenFileResponse success(boolean external, String message) {
            return new OpenFileResponse(true, external, message);
        }

        static OpenFileResponse failure(boolean external, String message) {
            return new OpenFileResponse(false, external, message);
        }

        public String toJson() {
            return "{"
                    + "\"success\":" + success + ","
                    + "\"external\":" + external + ","
                    + "\"message\":" + JsonUtil.quote(message)
                    + "}";
        }
    }
}
