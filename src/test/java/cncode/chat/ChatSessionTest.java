package cncode.chat;

public class ChatSessionTest {
    public static void main(String[] args) {
        ChatSession session = new ChatSession();
        session.addUserMessage("你好");
        session.addAssistantMessage("你好，我是 CN Code。");

        if (session.messages().size() != 2) {
            throw new AssertionError("消息数量不正确");
        }
        if (session.messages().get(0).role() != ChatRole.USER) {
            throw new AssertionError("第一条消息角色不正确");
        }
        if (session.messages().get(1).role() != ChatRole.ASSISTANT) {
            throw new AssertionError("第二条消息角色不正确");
        }
    }
}
