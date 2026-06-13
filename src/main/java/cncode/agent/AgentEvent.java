package cncode.agent;

import cncode.permission.PermissionRequest;
import cncode.tool.ToolResult;

public record AgentEvent(Type type, String text, String toolName, ToolResult toolResult, PermissionRequest permissionRequest) {
    public enum Type {
        USER_MESSAGE,
        THINKING_DELTA,
        DELTA,
        TOOL_START,
        TOOL_RESULT,
        PERMISSION_REQUEST,
        TURN_COMPLETE,
        LOOP_COMPLETE,
        COMPACT,
        CANCELLED,
        TIMEOUT,
        ERROR,
        DONE
    }

    public static AgentEvent userMessage(String text) {
        return new AgentEvent(Type.USER_MESSAGE, text, "", null, null);
    }

    public static AgentEvent thinkingDelta(String text) {
        return new AgentEvent(Type.THINKING_DELTA, text, "", null, null);
    }

    public static AgentEvent delta(String text) {
        return new AgentEvent(Type.DELTA, text, "", null, null);
    }

    public static AgentEvent toolStart(String toolName) {
        return new AgentEvent(Type.TOOL_START, "", toolName, null, null);
    }

    public static AgentEvent toolResult(ToolResult result) {
        return new AgentEvent(Type.TOOL_RESULT, "", result.toolName(), result, null);
    }

    public static AgentEvent permissionRequest(PermissionRequest request) {
        return new AgentEvent(Type.PERMISSION_REQUEST, request.reason(), request.toolName(), null, request);
    }

    public static AgentEvent turnComplete() {
        return new AgentEvent(Type.TURN_COMPLETE, "", "", null, null);
    }

    public static AgentEvent loopComplete() {
        return new AgentEvent(Type.LOOP_COMPLETE, "", "", null, null);
    }

    public static AgentEvent compact(String text) {
        return new AgentEvent(Type.COMPACT, text, "", null, null);
    }

    public static AgentEvent cancelled(String text) {
        return new AgentEvent(Type.CANCELLED, text, "", null, null);
    }

    public static AgentEvent timeout(String text) {
        return new AgentEvent(Type.TIMEOUT, text, "", null, null);
    }

    public static AgentEvent error(String text) {
        return new AgentEvent(Type.ERROR, text, "", null, null);
    }

    public static AgentEvent done() {
        return new AgentEvent(Type.DONE, "", "", null, null);
    }
}
