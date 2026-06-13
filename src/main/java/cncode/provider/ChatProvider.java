package cncode.provider;

public interface ChatProvider {
    void streamChat(ChatRequest request, StreamHandler handler) throws ProviderException;
}
