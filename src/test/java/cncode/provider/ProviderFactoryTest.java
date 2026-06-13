package cncode.provider;

import cncode.config.AppConfig;
import cncode.provider.anthropic.AnthropicProvider;
import cncode.provider.openai.OpenAiProvider;

public class ProviderFactoryTest {
    public static void main(String[] args) throws Exception {
        ProviderFactory factory = new ProviderFactory();

        ChatProvider openai = factory.create(new AppConfig("openai", "model", "https://example.test/v1", "key"));
        if (!(openai instanceof OpenAiProvider)) {
            throw new AssertionError("openai 未创建 OpenAiProvider");
        }

        ChatProvider anthropic = factory.create(new AppConfig("anthropic", "model", "https://example.test/v1", "key"));
        if (!(anthropic instanceof AnthropicProvider)) {
            throw new AssertionError("anthropic 未创建 AnthropicProvider");
        }

        try {
            factory.create(new AppConfig("other", "model", "https://example.test/v1", "key"));
            throw new AssertionError("未知协议应当失败");
        } catch (ProviderException expected) {
            // 预期失败。
        }
    }
}
