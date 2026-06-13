package cncode.session;

import java.nio.file.Path;

public record SessionInfo(String id, Path path, int records) {
}
