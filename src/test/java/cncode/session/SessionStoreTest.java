package cncode.session;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SessionStoreTest {
    public static void main(String[] args) throws Exception {
        appendsListsAndRestores();
        detectsTampering();
        rejectsMissingMiddleRecord();
    }

    private static void appendsListsAndRestores() throws Exception {
        Path root = Files.createTempDirectory("cncode-session");
        SessionStore store = new SessionStore(root, "test");
        store.append(new ChatMessage(ChatRole.USER, "hello"));
        store.append(new ChatMessage(ChatRole.ASSISTANT, "world"));

        if (!Files.isRegularFile(store.file()) || store.listSessions().isEmpty()) {
            throw new AssertionError("session file/list missing");
        }
        SessionRestoreResult restored = store.restore("test");
        if (!restored.success() || restored.messages().size() != 2 || !"hello".equals(restored.messages().getFirst().content())) {
            throw new AssertionError("restore failed: " + restored);
        }
    }

    private static void detectsTampering() throws Exception {
        Path root = Files.createTempDirectory("cncode-session-tamper");
        SessionStore store = new SessionStore(root, "test");
        store.append(new ChatMessage(ChatRole.USER, "hello"));
        String tampered = Files.readString(store.file(), StandardCharsets.UTF_8).replace("hello", "HELLO");
        Files.writeString(store.file(), tampered, StandardCharsets.UTF_8);

        if (store.restore("test").success()) {
            throw new AssertionError("tampered session should fail integrity check");
        }
    }

    private static void rejectsMissingMiddleRecord() throws Exception {
        Path root = Files.createTempDirectory("cncode-session-gap");
        SessionStore store = new SessionStore(root, "test");
        store.append(new ChatMessage(ChatRole.USER, "one"));
        store.append(new ChatMessage(ChatRole.ASSISTANT, "two"));
        store.append(new ChatMessage(ChatRole.USER, "three"));
        List<String> lines = Files.readAllLines(store.file(), StandardCharsets.UTF_8);
        Files.write(store.file(), List.of(lines.get(0), lines.get(2)), StandardCharsets.UTF_8);

        if (store.restore("test").success()) {
            throw new AssertionError("missing middle record should fail integrity check");
        }
    }
}
