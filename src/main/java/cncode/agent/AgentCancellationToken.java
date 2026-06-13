package cncode.agent;

import java.util.concurrent.atomic.AtomicBoolean;

public class AgentCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
