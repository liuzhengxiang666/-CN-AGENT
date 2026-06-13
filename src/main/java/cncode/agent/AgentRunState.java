package cncode.agent;

public enum AgentRunState {
    IDLE,
    RUNNING_MODEL,
    EXECUTING_TOOLS,
    COMPLETED,
    CANCELLED,
    FAILED
}
