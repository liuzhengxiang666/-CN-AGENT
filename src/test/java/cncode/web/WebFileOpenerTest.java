package cncode.web;

import java.nio.file.Files;
import java.nio.file.Path;

public class WebFileOpenerTest {
    public static void main(String[] args) throws Exception {
        validatesProjectFilesAndExternalConfirmation();
    }

    private static void validatesProjectFilesAndExternalConfirmation() throws Exception {
        Path root = Files.createTempDirectory("cncode-root");
        Path inside = Files.writeString(root.resolve("inside.txt"), "ok");
        Path external = Files.createTempFile("cncode-external", ".txt");
        WebFileOpener opener = new WebFileOpener(root);

        WebFileOpener.OpenFileResponse insideResult = opener.validate(inside.toString(), false);
        if (!insideResult.success() || insideResult.external()) {
            throw new AssertionError("project file should be allowed: " + insideResult);
        }

        WebFileOpener.OpenFileResponse blockedExternal = opener.validate(external.toString(), false);
        if (blockedExternal.success() || !blockedExternal.external()) {
            throw new AssertionError("external file should require confirmation: " + blockedExternal);
        }

        WebFileOpener.OpenFileResponse confirmedExternal = opener.validate(external.toString(), true);
        if (!confirmedExternal.success() || !confirmedExternal.external()) {
            throw new AssertionError("confirmed external file should pass: " + confirmedExternal);
        }

        WebFileOpener.OpenFileResponse missing = opener.validate(root.resolve("missing.txt").toString(), false);
        if (missing.success() || !missing.message().contains("文件不存在")) {
            throw new AssertionError("missing file should fail: " + missing);
        }
    }
}
