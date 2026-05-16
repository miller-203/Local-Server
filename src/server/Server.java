package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
                } else if (key.isReadable()) {
                    readFromClient(key);
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

        ClientConnection connection = new ClientConnection(clientChannel);

        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
        clientKey.attach(connection);

        System.out.println("New client connected: " + clientChannel.getRemoteAddress());
    }

    private void readFromClient(SelectionKey key) {
        ClientConnection connection = (ClientConnection) key.attachment();
        SocketChannel clientChannel = connection.getChannel();
        ByteBuffer readBuffer = connection.getReadBuffer();

        try {
            int bytesRead = clientChannel.read(readBuffer);

            if (bytesRead == -1) {
                closeClient(key);
                return;
            }

            if (bytesRead == 0) {
                return;
            }

            connection.updateLastActiveAt();

            readBuffer.flip();

            String rawRequest = StandardCharsets.UTF_8.decode(readBuffer).toString();

            System.out.println("----- RAW HTTP REQUEST -----");
            System.out.println(rawRequest);
            System.out.println("----------------------------");

            readBuffer.clear();

            closeClient(key);

        } catch (IOException e) {
            System.err.println("Read error: " + e.getMessage());
            closeClient(key);
        }
    }

    private void closeClient(SelectionKey key) {
        try {
            ClientConnection connection = (ClientConnection) key.attachment();

            if (connection != null) {
                connection.getChannel().close();
            }

            key.cancel();

        } catch (IOException e) {
            System.err.println("Close error: " + e.getMessage());
        }
    }
}