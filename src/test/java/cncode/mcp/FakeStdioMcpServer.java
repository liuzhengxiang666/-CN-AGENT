package cncode.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class FakeStdioMcpServer {
    public static void main(String[] args) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("notifications/initialized")) {
                    continue;
                }
                long id = line.contains("\"id\":") ? Long.parseLong(line.replaceFirst(".*\"id\":(\\d+).*", "$1")) : 0;
                if (line.contains("\"method\":\"initialize\"")) {
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"instructions\":\"fake\"}}");
                } else if (line.contains("\"method\":\"tools/list\"")) {
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]}}");
                } else if (line.contains("\"method\":\"tools/call\"")) {
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"stdio ok\"}],\"isError\":false}}");
                }
                System.out.flush();
            }
        }
    }
}
