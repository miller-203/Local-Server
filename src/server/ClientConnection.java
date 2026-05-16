package server;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ClientConnection {
    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;

    private final long connectedAt;
    private long lastActiveAt;

    public ClientConnection(SocketChannel channel) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.writeBuffer = null;

        long now = System.currentTimeMillis();
        this.connectedAt = now;
        this.lastActiveAt = now;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public ByteBuffer getWriteBuffer() {
        return writeBuffer;
    }

    public void setWriteBuffer(ByteBuffer writeBuffer) {
        this.writeBuffer = writeBuffer;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public void updateLastActiveAt() {
        this.lastActiveAt = System.currentTimeMillis();
    }
}