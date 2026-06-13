package cncode.session;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.provider.JsonUtil;
import cncode.provider.ProviderException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class SessionStore {
    public static final String SESSION_DIR = ".cncode/sessions";

    private final Path workDir;
    private final Path sessionsDir;
    private final String id;
    private final Path file;
    private int nextIndex;
    private String previousHash = "ROOT";

    public SessionStore(Path workDir, String id) {
        this.workDir = (workDir == null ? Path.of("") : workDir).toAbsolutePath().normalize();
        this.sessionsDir = this.workDir.resolve(SESSION_DIR).normalize();
        this.id = sanitize(id == null || id.isBlank() ? "session-" + System.currentTimeMillis() : id);
        this.file = sessionsDir.resolve(this.id + ".jsonl").normalize();
        loadTail();
    }

    public String id() {
        return id;
    }

    public Path file() {
        return file;
    }

    public void append(ChatMessage message) throws IOException {
        Files.createDirectories(sessionsDir);
        String hash = hash(nextIndex, message.role(), message.content(), previousHash);
        SessionRecord record = SessionRecord.create(nextIndex, message, previousHash, hash);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(record.toJson());
            writer.newLine();
        }
        previousHash = hash;
        nextIndex++;
    }

    public List<SessionInfo> listSessions() throws IOException {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<SessionInfo> result = new ArrayList<>();
        try (var stream = Files.list(sessionsDir)) {
            for (Path path : stream.filter(path -> path.getFileName().toString().endsWith(".jsonl")).sorted(Comparator.comparing(Path::toString)).toList()) {
                result.add(new SessionInfo(stripJsonl(path.getFileName().toString()), path, countLines(path)));
            }
        }
        return result;
    }

    public SessionRestoreResult restore(String requestedId) {
        String safeId = sanitize(requestedId);
        if (safeId.isBlank()) {
            return SessionRestoreResult.failure("会话 id 不能为空");
        }
        Path target = sessionsDir.resolve(safeId + ".jsonl").normalize();
        if (!target.startsWith(sessionsDir)) {
            return SessionRestoreResult.failure("会话路径越界");
        }
        if (!Files.isRegularFile(target)) {
            return SessionRestoreResult.failure("找不到会话：" + safeId);
        }
        try {
            List<SessionRecord> records = readRecords(target);
            return SessionRestoreResult.success(records.stream().map(SessionRecord::toMessage).toList());
        } catch (Exception error) {
            return SessionRestoreResult.failure("会话完整性校验失败：" + error.getMessage());
        }
    }

    private void loadTail() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<SessionRecord> records = readRecords(file);
            if (!records.isEmpty()) {
                SessionRecord last = records.getLast();
                nextIndex = last.index() + 1;
                previousHash = last.hash();
            }
        } catch (Exception ignored) {
            nextIndex = 0;
            previousHash = "ROOT";
        }
    }

    private List<SessionRecord> readRecords(Path path) throws IOException, ProviderException {
        List<SessionRecord> records = new ArrayList<>();
        String previous = "ROOT";
        int expectedIndex = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            SessionRecord record = fromJson(line);
            if (record.index() != expectedIndex) {
                throw new IOException("消息序号不连续");
            }
            if (!previous.equals(record.previousHash())) {
                throw new IOException("previousHash 不匹配");
            }
            String expectedHash = hash(record.index(), record.role(), record.content(), record.previousHash());
            if (!expectedHash.equals(record.hash())) {
                throw new IOException("hash 不匹配");
            }
            records.add(record);
            previous = record.hash();
            expectedIndex++;
        }
        return records;
    }

    private SessionRecord fromJson(String json) throws ProviderException {
        int index = Integer.parseInt(extractNumber(json, "index"));
        ChatRole role = ChatRole.valueOf(JsonUtil.extractStringField(json, "role"));
        String content = JsonUtil.extractStringField(json, "content");
        String timestamp = JsonUtil.extractStringField(json, "timestamp");
        String previousHash = JsonUtil.extractStringField(json, "previousHash");
        String hash = JsonUtil.extractStringField(json, "hash");
        return new SessionRecord(index, role, content, timestamp, previousHash, hash);
    }

    private String extractNumber(String json, String field) {
        String key = JsonUtil.quote(field);
        int keyIndex = json.indexOf(key);
        int colon = json.indexOf(':', keyIndex + key.length());
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return json.substring(start, end);
    }

    private String hash(int index, ChatRole role, String content, String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = index + "\n" + role.name() + "\n" + content + "\n" + previousHash;
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private int countLines(Path path) {
        try {
            return (int) Files.lines(path, StandardCharsets.UTF_8).filter(line -> !line.isBlank()).count();
        } catch (IOException error) {
            return 0;
        }
    }

    private String stripJsonl(String name) {
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "");
    }
}
