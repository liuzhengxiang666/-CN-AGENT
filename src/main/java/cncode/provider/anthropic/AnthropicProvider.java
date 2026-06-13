package cncode.provider.anthropic;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.JsonUtil;
import cncode.provider.ProviderException;
import cncode.provider.ProviderHttpUtil;
import cncode.provider.StreamHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

public class AnthropicProvider implements ChatProvider {
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final AppConfig config;
    private final HttpClient httpClient;

    public AnthropicProvider(AppConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public void streamChat(ChatRequest request, StreamHandler handler) throws ProviderException {
        URI endpoint = ProviderHttpUtil.endpoint(config.baseUrl(), "/messages");
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request)))
                .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("Anthropic 请求失败，HTTP 状态码：" + response.statusCode());
            }
            parseSse(response.body(), handler);
        } catch (IOException error) {
            throw new ProviderException("Anthropic 网络请求失败。", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Anthropic 请求被中断。", error);
        }
    }

    private String buildBody(ChatRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        builder.append("\"model\":").append(JsonUtil.quote(request.model())).append(',');
        builder.append("\"max_tokens\":").append(DEFAULT_MAX_TOKENS).append(',');
        builder.append("\"stream\":true,");
        builder.append("\"messages\":[");
        int writtenMessages = 0;
        for (ChatMessage message : request.messages()) {
            if (message.role() == ChatRole.SYSTEM) {
                continue;
            }
            if (writtenMessages > 0) {
                builder.append(',');
            }
            builder.append('{')
                    .append("\"role\":").append(JsonUtil.quote(toAnthropicRole(message.role()))).append(',')
                    .append("\"content\":").append(JsonUtil.quote(message.content()))
                    .append('}');
            writtenMessages++;
        }
        builder.append("]}");
        return builder.toString();
    }

    private String toAnthropicRole(ChatRole role) {
        return role == ChatRole.ASSISTANT ? "assistant" : "user";
    }

    private void parseSse(Stream<String> lines, StreamHandler handler) throws ProviderException {
        try (lines) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                String deltaType = JsonUtil.extractStringField(data, "type");
                if ("message_stop".equals(deltaType)) {
                    handler.onComplete("end_turn");
                    return;
                }
                String stopReason = JsonUtil.extractStringField(data, "stop_reason");
                if (stopReason != null && !stopReason.isBlank()) {
                    handler.onComplete(stopReason);
                    return;
                }
                String text = JsonUtil.extractStringField(data, "text");
                if (text != null && !text.isEmpty()) {
                    handler.onDelta(text);
                }
            }
            handler.onComplete();
        }
    }
}
