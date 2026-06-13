package cncode.provider;

import cncode.config.AppConfig;
import cncode.provider.anthropic.AnthropicProvider;
import cncode.provider.openai.OpenAiProvider;

import java.net.http.HttpClient;

public class ProviderFactory {
    private final HttpClient httpClient;

    public ProviderFactory() {
        this(HttpClient.newHttpClient());
    }

    public ProviderFactory(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public ChatProvider create(AppConfig config) throws ProviderException {
        return switch (config.protocol().toLowerCase()) {
            case "openai" -> new OpenAiProvider(config, httpClient);
            case "anthropic" -> new AnthropicProvider(config, httpClient);
            default -> throw new ProviderException("不支持的 protocol：" + config.protocol() + "。支持值：openai, anthropic。");
        };
    }
}
