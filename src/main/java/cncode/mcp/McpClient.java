package cncode.mcp;

import java.util.List;
import java.util.Map;

public interface McpClient extends AutoCloseable {
    void connect() throws Exception;

    List<McpToolDef> listTools() throws Exception;

    McpCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    boolean isAlive();

    @Override
    void close();
}
