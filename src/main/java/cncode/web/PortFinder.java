package cncode.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class PortFinder {
    private final String host;
    private final int startPort;
    private final int endPort;

    public PortFinder() {
        this("127.0.0.1", 8765, 8865);
    }

    public PortFinder(String host, int startPort, int endPort) {
        this.host = host;
        this.startPort = startPort;
        this.endPort = endPort;
    }

    public int findAvailablePort() throws IOException {
        for (int port = startPort; port <= endPort; port++) {
            if (isAvailable(port)) {
                return port;
            }
        }
        throw new IOException("没有找到可用端口：" + startPort + "-" + endPort);
    }

    private boolean isAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
            return true;
        } catch (IOException error) {
            return false;
        }
    }
}
