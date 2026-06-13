package cncode.toolresult;

import cncode.tool.ToolJson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class ReplacementRecordsIO {
    public static final String RECORDS_FILENAME = "replacement_records.jsonl";

    private ReplacementRecordsIO() {
    }

    public static void append(Path sessionDir, List<ContentReplacementRecord> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        Files.createDirectories(sessionDir);
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        try (BufferedWriter writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            for (ContentReplacementRecord record : records) {
                writer.write(toJson(record));
                writer.newLine();
            }
        }
    }

    public static List<ContentReplacementRecord> load(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<ContentReplacementRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(fromJson(line));
            }
        }
        return records;
    }

    private static String toJson(ContentReplacementRecord record) {
        return "{"
                + "\"kind\":" + ToolJson.quote(record.kind()) + ","
                + "\"toolUseId\":" + ToolJson.quote(record.toolUseId()) + ","
                + "\"replacement\":" + ToolJson.quote(record.replacement())
                + "}";
    }

    private static ContentReplacementRecord fromJson(String json) {
        return new ContentReplacementRecord(
                extract(json, "kind"),
                extract(json, "toolUseId"),
                extract(json, "replacement")
        );
    }

    private static String extract(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) {
            return "";
        }
        int quote = json.indexOf('"', start + pattern.length());
        if (quote < 0) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int index = quote + 1; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (escaping) {
                value.append(switch (ch) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"', '\\' -> ch;
                    default -> ch;
                });
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                return value.toString();
            } else {
                value.append(ch);
            }
        }
        return value.toString();
    }
}
