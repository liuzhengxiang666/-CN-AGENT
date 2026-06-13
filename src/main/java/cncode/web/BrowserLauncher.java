package cncode.web;

import java.awt.Desktop;
import java.net.URI;

public class BrowserLauncher {
    public boolean open(URI uri) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(uri);
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
