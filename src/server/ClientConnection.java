package server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientConnection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private final ByteArrayOutputStream requestBuffer;
    private final String listenHost;
    private final int listenPort;
    private final String remoteAddress;
    private ByteBuffer writeBuffer;

    private long lastActiveAt;

    public ClientConnection(SocketChannel channel, String listenHost, int listenPort, String remoteAddress) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.requestBuffer = new ByteArrayOutputStream();
        this.listenHost = listenHost;
        this.listenPort = listenPort;
        this.remoteAddress = remoteAddress;
        this.writeBuffer = null;

        long now = System.currentTimeMillis();
        this.lastActiveAt = now;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public byte[] getRequestBytes() {
        return requestBuffer.toByteArray();
    }

    public void appendRequestBytes(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        requestBuffer.writeBytes(bytes);
    }

    public String getListenHost() {
        return listenHost;
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }

    public void setWriteBuffer(ByteBuffer writeBuffer) {
        this.writeBuffer = writeBuffer;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public void updateLastActiveAt() {
        this.lastActiveAt = System.currentTimeMillis();
    }
}
