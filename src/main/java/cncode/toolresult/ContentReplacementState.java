package cncode.toolresult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ContentReplacementState {
    private final Set<String> seenIds;
    private final Map<String, String> replacements;

    public ContentReplacementState() {
        this(new HashSet<>(), new HashMap<>());
    }

    private ContentReplacementState(Set<String> seenIds, Map<String, String> replacements) {
        this.seenIds = seenIds;
        this.replacements = replacements;
    }

    public Set<String> seenIds() {
        return seenIds;
    }

    public Map<String, String> replacements() {
        return replacements;
    }

    public ContentReplacementState copy() {
        return new ContentReplacementState(new HashSet<>(seenIds), new HashMap<>(replacements));
    }
}
