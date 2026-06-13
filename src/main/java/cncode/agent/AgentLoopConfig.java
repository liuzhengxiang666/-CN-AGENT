package cncode.agent;

import cncode.permission.PermissionMode;

import java.time.Duration;

public record AgentLoopConfig(int maxIterations, Duration modelIdleTimeout, Duration toolTimeout, int maxOutputChars, boolean planOnly,
                              PermissionMode permissionMode, Duration permissionTimeout) {
    public AgentLoopConfig(int maxIterations, Duration toolTimeout, int maxOutputChars) {
        this(maxIterations, Duration.ofSeconds(60), toolTimeout, maxOutputChars, false, PermissionMode.DEFAULT, Duration.ofMinutes(5));
    }

    public AgentLoopConfig(int maxIterations, Duration toolTimeout, int maxOutputChars, boolean planOnly) {
        this(maxIterations, Duration.ofSeconds(60), toolTimeout, maxOutputChars, planOnly, PermissionMode.DEFAULT, Duration.ofMinutes(5));
    }

    public AgentLoopConfig(int maxIterations, Duration modelIdleTimeout, Duration toolTimeout, int maxOutputChars, boolean planOnly) {
        this(maxIterations, modelIdleTimeout, toolTimeout, maxOutputChars, planOnly, PermissionMode.DEFAULT, Duration.ofMinutes(5));
    }

    public AgentLoopConfig {
        maxIterations = maxIterations <= 0 ? 10 : maxIterations;
        modelIdleTimeout = modelIdleTimeout == null || modelIdleTimeout.isNegative() || modelIdleTimeout.isZero()
                ? Duration.ofSeconds(60)
                : modelIdleTimeout;
        toolTimeout = toolTimeout == null || toolTimeout.isNegative() || toolTimeout.isZero()
                ? Duration.ofSeconds(30)
                : toolTimeout;
        maxOutputChars = maxOutputChars <= 0 ? 12000 : maxOutputChars;
        permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
        permissionTimeout = permissionTimeout == null || permissionTimeout.isNegative() || permissionTimeout.isZero()
                ? Duration.ofMinutes(5)
                : permissionTimeout;
    }

    public static AgentLoopConfig defaults() {
        return new AgentLoopConfig(10, Duration.ofSeconds(60), Duration.ofSeconds(30), 12000, false, PermissionMode.DEFAULT, Duration.ofMinutes(5));
    }
}
