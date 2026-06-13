package cncode.provider.openai;

import cncode.chat.ChatMessage;
import cncode.chat.ChatRole;
import cncode.config.AppConfig;
import cncode.provider.ChatProvider;
import cncode.provider.ChatRequest;
import cncode.provider.JsonUtil;
import cncode.provider.ProviderException;
import cncode.provider.ProviderHttpUtil;
import cncode.provider.StreamHandler;
import cncode.tool.ToolCall;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

public class OpenAiProvider implements ChatProvider {
    private final AppConfig config;
    private final HttpClient httpClient;

    public OpenAiProvider(AppConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public void streamChat(ChatRequest request, StreamHandler handler) throws ProviderException {
        URI endpoint = ProviderHttpUtil.endpoint(config.baseUrl(), "/chat/completions");
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request)))
                .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException("OpenAI 请求失败，HTTP 状态码：" + response.statusCode());
            }
            parseSse(response.body(), handler);
        } catch (IOException error) {
            throw new ProviderException("OpenAI 网络请求失败。", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException("OpenAI 请求被中断。", error);
        }
    }

    private String buildBody(ChatRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        builder.append("\"model\":").append(JsonUtil.quote(request.model())).append(',');
        builder.append("\"stream\":true,");
        builder.append("\"messages\":[");
        for (int i = 0; i < request.messages().size(); i++) {
            ChatMessage message = request.messages().get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{')
                    .append("\"role\":").append(JsonUtil.quote(toOpenAiRole(message.role()))).append(',')
                    .append("\"content\":").append(JsonUtil.quote(message.content()))
                    .append('}');
        }
        builder.append("]");
        if (request.allowTools() && !"[]".equals(request.toolsJson())) {
            builder.append(",\"tools\":").append(request.toolsJson());
            builder.append(",\"tool_choice\":\"auto\"");
        }
        builder.append("}");
        return builder.toString();
    }

    private String toOpenAiRole(ChatRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }

    private void parseSse(Stream<String> lines, StreamHandler handler) throws ProviderException {
        ToolCallBuilder toolCallBuilder = new ToolCallBuilder();
        try (lines) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    handler.onComplete();
                    return;
                }
                String delta = JsonUtil.extractStringField(data, "content");
                if (delta != null && !delta.isEmpty()) {
                    handler.onDelta(delta);
                }
                String toolId = JsonUtil.extractStringField(data, "id");
                if (toolId != null && toolId.startsWith("call_")) {
                    toolCallBuilder.id = toolId;
                }
                String functionName = JsonUtil.extractStringField(data, "name");
                if (functionName != null && !functionName.isBlank()) {
                    toolCallBuilder.name = functionName;
                }
                String arguments = JsonUtil.extractStringField(data, "arguments");
                if (arguments != null) {
                    toolCallBuilder.arguments.append(arguments);
                }
                String finishReason = JsonUtil.extractStringField(data, "finish_reason");
                if ("tool_calls".equals(finishReason)) {
                    handler.onToolCall(toolCallBuilder.build());
                    handler.onComplete(finishReason);
                    return;
                }
                if (finishReason != null && !finishReason.isBlank()) {
                    handler.onComplete(finishReason);
                    return;
                }
            }
            handler.onComplete();
        }
    }

    private static class ToolCallBuilder {
        private String id = "tool_call_0";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        private ToolCall build() {
            return new ToolCall(id, name, arguments.toString());
        }
    }
}
