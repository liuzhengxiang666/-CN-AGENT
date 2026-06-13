package cncode.mcp;

import cncode.config.McpServerConfig;

import java.util.List;
import java.util.Map;

public class McpStdioClientTest {
    public static void main(String[] args) throws Exception {
        stdioClientListsAndCallsTools();
    }

    private static void stdioClientListsAndCallsTools() throws Exception {
        String javaBin = PathUtil.javaBin();
        String classpath = System.getProperty("java.class.path");
        McpStdioClient client = new McpStdioClient(new McpServerConfig(
                "fake",
                javaBin,
                List.of("-cp", classpath, "cncode.mcp.FakeStdioMcpServer"),
                Map.of(),
                "",
                Map.of(),
                5
        ));
        client.connect();
        List<McpToolDef> tools = client.listTools();
        if (tools.size() != 1 || !"echo".equals(tools.get(0).name())) {
            throw new AssertionError("stdio tools invalid: " + tools);
        }
        McpCallResult result = client.callTool("echo", Map.of("text", "hi"));
        if (result.error() || !"stdio ok".equals(result.text())) {
            throw new AssertionError("stdio call invalid: " + result);
        }
        client.close();
    }

    private static final class PathUtil {
        static String javaBin() {
            String home = System.getProperty("java.home");
            String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            return java.nio.file.Path.of(home, "bin", exe).toString();
        }
    }
}
