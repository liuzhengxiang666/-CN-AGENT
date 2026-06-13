package cncode.compact;

public final class CompactTrackingState {
    private int consecutiveFailures;

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
    }

    public void recordFailure() {
        consecutiveFailures++;
    }
}
