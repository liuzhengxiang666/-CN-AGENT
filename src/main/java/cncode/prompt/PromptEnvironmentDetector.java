package cncode.prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class PromptEnvironmentDetector {
    private PromptEnvironmentDetector() {
    }

    public static PromptEnvironment detect(Path workDir) {
        Path root = workDir == null ? Path.of("").toAbsolutePath() : workDir.toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", "");
        String shell = detectShell();
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        boolean gitRepo = "true".equals(runGit(root, "rev-parse", "--is-inside-work-tree"));
        String branch = gitRepo ? runGit(root, "branch", "--show-current") : "";
        String status = gitRepo ? runGit(root, "status", "--short") : "";
        return new PromptEnvironment(root.toString(), os.strip(), shell, dateTime, gitRepo, branch, status);
    }

    private static String detectShell() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            return shell;
        }
        String comspec = System.getenv("COMSPEC");
        if (comspec != null && !comspec.isBlank()) {
            return comspec;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "powershell" : "sh";
    }

    private static String runGit(Path workDir, String... args) {
        try {
            String[] command = new String[args.length + 3];
            command[0] = "git";
            command[1] = "-C";
            command[2] = workDir.toString();
            System.arraycopy(args, 0, command, 3, args.length);
            Process process = new ProcessBuilder(command).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception ignored) {
            return "";
        }
    }
}
