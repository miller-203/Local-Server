package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.util.Iterator;

public class Server {
    private final String host;
    private final int port;

    private ServerSocketChannel serverChannel;
    private Selector selector;

    public Server(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        try {
            setupServer();
            runEventLoop();
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void setupServer() throws IOException {
        selector = Selector.open();

        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(host, port));

        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Server listening on http://" + host + ":" + port);
    }

    private void runEventLoop() throws IOException {
        while (true) {
            selector.select();

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) {
                    continue;
                }

                if (key.isAcceptable()) {
                    acceptClient();
                }
            }
        }
    }

    private void acceptClient() throws IOException {
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel == null) {
            return;
        }

        clientChannel.configureBlocking(false);

        System.out.println("New client connected: " + clientChannel.getRemoteAddress());

        clientChannel.close();
    }
}