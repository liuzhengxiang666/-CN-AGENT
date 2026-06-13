package cncode.web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class PortFinderTest {
    public static void main(String[] args) throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int occupied = socket.getLocalPort();
            int next = occupied + 1;
            int found = new PortFinder("127.0.0.1", occupied, next + 10).findAvailablePort();
            if (found == occupied) {
                throw new AssertionError("occupied port should be skipped");
            }
        }
    }
}
