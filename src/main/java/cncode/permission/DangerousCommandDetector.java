package cncode.permission;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DangerousCommandDetector {
    private static final Pattern[] PATTERNS = {
            Pattern.compile("\\brm\\s+-[\\w-]*r[\\w-]*f[\\w-]*\\s+(/|~|\\*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdel\\s+[^\\n]*(/s|/q)[^\\n]*(/q|/s)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\s+[^\\n]*(/s|/q)[^\\n]*(/q|/s)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bremove-item\\b[^\\n]*(?:-recurse|-r)[^\\n]*(?:-force|-fo)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sh|bash)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^|\\n]*\\|\\s*(iex|invoke-expression)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+reset\\s+--hard\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+clean\\s+-[a-z]*f[a-z]*d[a-z]*x[a-z]*\\b", Pattern.CASE_INSENSITIVE)
    };

    private DangerousCommandDetector() {
    }

    public static String reason(String command) {
        String text = command == null ? "" : command.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                return "命令命中危险操作黑名单：" + text;
            }
        }
        if (lower.contains("format c:") || lower.contains("mkfs.")) {
            return "命令命中危险格式化操作黑名单：" + text;
        }
        return "";
    }

    public static boolean dangerous(String command) {
        return !reason(command).isBlank();
    }
}
