package cncode.toolresult;

import cncode.chat.ChatMessage;

import java.util.List;

public record ApplyResult(List<ChatMessage> apiMessages, List<ContentReplacementRecord> newRecords) {
    public ApplyResult {
        apiMessages = List.copyOf(apiMessages);
        newRecords = List.copyOf(newRecords);
    }
}
