package server;

import config.ServerConfig;
import config.VirtualServerConfig;
import handlers.ErrorResponseFactory;
import http.HttpParser;
import http.HttpRequest;
import http.HttpResponse;
import routing.Router;
import utils.Metrics;
import utils.ServerLogger;
import utils.SessionManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

public class Server {
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final ServerConfig config;
    private final HttpParser parser;
    private final ErrorResponseFactory errors;
    private final Metrics metrics;
    private final SessionManager sessionManager;

    private Selector selector;
    private Router router;
    private ServerLogger logger;

    public Server(ServerConfig config) {
        this.config = config;
        this.parser = new HttpParser();
        this.errors = new ErrorResponseFactory();
        this.metrics = new Metrics();
        this.sessionManager = new SessionManager();
    }

    public void start() {
        try {
            setupServer();
            runEventLoop();
        } catch (IOException e) {
            logError("Server error", e);
        }
    }

    private void setupServer() throws IOException {
        logger = new ServerLogger(config.getLogDirectory());
        selector = Selector.open();
        router = new Router(metrics, sessionManager);

        for (ServerConfig.ListenAddress address : config.getListenAddresses()) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(address.host(), address.port()));

            SelectionKey serverKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            serverKey.attach(address);

            String message = "Server listening on http://" + address.host() + ":" + address.port();
            logger.server(message);
            System.out.println(message);
        }
    }

    private void runEventLoop() throws IOException {
        while (true) {
            selector.select(1_000);
            closeIdleClients();

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) {
                    continue;
                }

                try {
                    if (key.isAcceptable()) {
                        acceptClient(key);
                    } else if (key.isReadable()) {
                        readFromClient(key);
                    } else if (key.isWritable()) {
                        writeToClient(key);
                    }
                } catch (IOException e) {
                    logError("Socket error", e);
                    closeClient(key);
                }
            }
        }
    }

    private void acceptClient(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();

        if (clientChannel == null) {
            return;
        }

        clientChannel.configureBlocking(false);
        ServerConfig.ListenAddress address = (ServerConfig.ListenAddress) key.attachment();
        String remoteAddress = String.valueOf(clientChannel.getRemoteAddress());
        ClientConnection connection = new ClientConnection(
                clientChannel,
                address.host(),
                address.port(),
                remoteAddress);

        SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
        clientKey.attach(connection);
        metrics.connectionAccepted();
    }

    private void readFromClient(SelectionKey key) throws IOException {
        ClientConnection connection = (ClientConnection) key.attachment();
        SocketChannel clientChannel = connection.getChannel();
        ByteBuffer readBuffer = connection.getReadBuffer();

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
        connection.appendRequestBytes(readBuffer);
        readBuffer.clear();

        RequestReadResult result = tryReadRequest(connection);

        if (result.status() == RequestReadStatus.INCOMPLETE) {
            return;
        }

        if (result.status() == RequestReadStatus.BAD_REQUEST) {
            prepareResponse(key, connection, errorResponse(connection, 400), null);
            return;
        }

        if (result.status() == RequestReadStatus.BODY_TOO_LARGE) {
            prepareResponse(key, connection, errorResponse(connection, 413), null);
            return;
        }

        HttpRequest request = result.request();
        VirtualServerConfig server = result.server();
        SessionManager.SessionContext sessionContext = sessionManager.resolve(request);
        HttpResponse response = router.route(request, server, sessionContext);

        if (sessionContext.isFresh()) {
            response.addHeader("Set-Cookie", sessionContext.setCookieHeader());
        }

        prepareResponse(key, connection, response, request);
    }

    private RequestReadResult tryReadRequest(ClientConnection connection) {
        byte[] data = connection.getRequestBytes();
        int headerEnd = findHeaderEnd(data, 0);

        if (headerEnd == -1) {
            if (data.length > MAX_HEADER_BYTES) {
                return RequestReadResult.bad();
            }

            return RequestReadResult.incomplete();
        }

        byte[] headerBytes = Arrays.copyOfRange(data, 0, headerEnd);
        int bodyStart = headerEnd + headerDelimiterLength(data, headerEnd);
        HttpRequest head;

        try {
            head = parser.parseHead(headerBytes);
        } catch (IllegalArgumentException e) {
            logError("Bad request", e);
            return RequestReadResult.bad();
        }

        if ("HTTP/1.1".equals(head.getVersion()) && head.getHeader("Host") == null) {
            return RequestReadResult.bad();
        }

        VirtualServerConfig server = config.findVirtualServer(
                connection.getListenHost(),
                connection.getListenPort(),
                head.getHeader("Host"));
        long bodyLimit = server.getClientMaxBodySize();
        String transferEncoding = head.getHeader("Transfer-Encoding");
        String contentLengthHeader = head.getHeader("Content-Length");
        byte[] bodyBytes;

        if (transferEncoding != null) {
            if (!transferEncoding.toLowerCase().contains("chunked") || contentLengthHeader != null) {
                return RequestReadResult.bad();
            }

            ChunkedDecodeResult chunked = decodeChunked(Arrays.copyOfRange(data, bodyStart, data.length), bodyLimit);

            if (chunked.status() == RequestReadStatus.INCOMPLETE) {
                return RequestReadResult.incomplete();
            }

            if (chunked.status() == RequestReadStatus.BODY_TOO_LARGE) {
                return RequestReadResult.tooLarge();
            }

            if (chunked.status() == RequestReadStatus.BAD_REQUEST) {
                return RequestReadResult.bad();
            }

            bodyBytes = chunked.body();
        } else if (contentLengthHeader != null) {
            long contentLength;

            try {
                contentLength = Long.parseLong(contentLengthHeader.trim());
            } catch (NumberFormatException e) {
                return RequestReadResult.bad();
            }

            if (contentLength < 0) {
                return RequestReadResult.bad();
            }

            if (contentLength > bodyLimit) {
                return RequestReadResult.tooLarge();
            }

            long available = data.length - bodyStart;

            if (available < contentLength) {
                return RequestReadResult.incomplete();
            }

            bodyBytes = Arrays.copyOfRange(data, bodyStart, bodyStart + (int) contentLength);
        } else {
            bodyBytes = new byte[0];
        }

        try {
            HttpRequest request = parser.parse(headerBytes, bodyBytes);
            request.setLocalPort(connection.getListenPort());
            request.setRemoteAddress(connection.getRemoteAddress());
            return RequestReadResult.complete(request, server);
        } catch (IllegalArgumentException e) {
            logError("Bad request", e);
            return RequestReadResult.bad();
        }
    }

    private ChunkedDecodeResult decodeChunked(byte[] data, long bodyLimit) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int index = 0;

        while (true) {
            int lineEnd = findLineEnd(data, index);

            if (lineEnd == -1) {
                return ChunkedDecodeResult.incomplete();
            }

            String sizeLine = new String(data, index, lineEnd - index, StandardCharsets.ISO_8859_1);
            String sizeText = sizeLine.split(";", 2)[0].trim();
            int chunkSize;

            try {
                chunkSize = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException e) {
                return ChunkedDecodeResult.bad();
            }

            index = lineEnd + lineDelimiterLength(data, lineEnd);

            if (chunkSize == 0) {
                if (hasBlankLine(data, index)) {
                    return ChunkedDecodeResult.complete(decoded.toByteArray());
                }

                int trailerEnd = findHeaderEnd(data, index);

                if (trailerEnd == -1) {
                    return ChunkedDecodeResult.incomplete();
                }

                return ChunkedDecodeResult.complete(decoded.toByteArray());
            }

            if ((long) decoded.size() + chunkSize > bodyLimit) {
                return ChunkedDecodeResult.tooLarge();
            }

            if (data.length < index + chunkSize + 1) {
                return ChunkedDecodeResult.incomplete();
            }

            decoded.write(data, index, chunkSize);
            index += chunkSize;

            if (index >= data.length) {
                return ChunkedDecodeResult.incomplete();
            }

            if (data[index] == '\r') {
                if (index + 1 >= data.length) {
                    return ChunkedDecodeResult.incomplete();
                }

                if (data[index + 1] != '\n') {
                    return ChunkedDecodeResult.bad();
                }

                index += 2;
            } else if (data[index] == '\n') {
                index += 1;
            } else {
                return ChunkedDecodeResult.bad();
            }
        }
    }

    private void writeToClient(SelectionKey key) throws IOException {
        ClientConnection connection = (ClientConnection) key.attachment();
        ByteBuffer writeBuffer = connection.getWriteBuffer();

        if (writeBuffer == null) {
            closeClient(key);
            return;
        }

        connection.getChannel().write(writeBuffer);
        connection.updateLastActiveAt();

        if (writeBuffer.hasRemaining()) {
            return;
        }

        long duration = System.currentTimeMillis() - connection.getRequestStartedAt();
        metrics.responseCompleted(connection.getResponseStatusCode());

        if (logger != null) {
            logger.request(
                    connection.getRemoteAddress(),
                    connection.getRequestMethod(),
                    connection.getRequestPath(),
                    connection.getResponseStatusCode(),
                    duration);
        }

        closeClient(key);
    }

    private void prepareResponse(
            SelectionKey key,
            ClientConnection connection,
            HttpResponse response,
            HttpRequest request) {
        connection.setWriteBuffer(ByteBuffer.wrap(response.toBytes()));
        connection.setResponseStatusCode(response.getStatusCode());

        if (request != null) {
            connection.setRequestMethod(request.getMethod());
            connection.setRequestPath(request.getPath());
        }

        key.interestOps(SelectionKey.OP_WRITE);
    }

    private HttpResponse errorResponse(ClientConnection connection, int statusCode) {
        VirtualServerConfig server = config.findVirtualServer(
                connection.getListenHost(),
                connection.getListenPort(),
                "");
        return errors.create(server, statusCode);
    }

    private void closeIdleClients() {
        long now = System.currentTimeMillis();

        for (SelectionKey key : selector.keys()) {
            if (!(key.attachment() instanceof ClientConnection connection)) {
                continue;
            }

            if (now - connection.getLastActiveAt() > config.getClientTimeoutMillis()) {
                closeClient(key);
            }
        }
    }

    private void closeClient(SelectionKey key) {
        try {
            if (key.attachment() instanceof ClientConnection connection) {
                connection.getChannel().close();
                metrics.connectionClosed();
            }

            key.cancel();
        } catch (IOException e) {
            logError("Close error", e);
        }
    }

    private int findHeaderEnd(byte[] data, int start) {
        for (int i = start; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }

        for (int i = start; i < data.length - 1; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                return i;
            }
        }

        return -1;
    }

    private int headerDelimiterLength(byte[] data, int headerEnd) {
        if (headerEnd + 3 < data.length
                && data[headerEnd] == '\r'
                && data[headerEnd + 1] == '\n'
                && data[headerEnd + 2] == '\r'
                && data[headerEnd + 3] == '\n') {
            return 4;
        }

        return 2;
    }

    private int findLineEnd(byte[] data, int start) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == '\n') {
                if (i > start && data[i - 1] == '\r') {
                    return i - 1;
                }

                return i;
            }
        }

        return -1;
    }

    private int lineDelimiterLength(byte[] data, int lineEnd) {
        if (lineEnd + 1 < data.length && data[lineEnd] == '\r' && data[lineEnd + 1] == '\n') {
            return 2;
        }

        return 1;
    }

    private boolean hasBlankLine(byte[] data, int index) {
        if (index >= data.length) {
            return false;
        }

        if (data[index] == '\n') {
            return true;
        }

        return index + 1 < data.length && data[index] == '\r' && data[index + 1] == '\n';
    }

    private void logError(String message, Throwable throwable) {
        if (logger != null) {
            logger.error(message, throwable);
        } else {
            System.err.println(message + ": " + throwable.getMessage());
        }
    }

    private enum RequestReadStatus {
        INCOMPLETE,
        COMPLETE,
        BAD_REQUEST,
        BODY_TOO_LARGE
    }

    private record RequestReadResult(
            RequestReadStatus status,
            HttpRequest request,
            VirtualServerConfig server) {
        static RequestReadResult incomplete() {
            return new RequestReadResult(RequestReadStatus.INCOMPLETE, null, null);
        }

        static RequestReadResult bad() {
            return new RequestReadResult(RequestReadStatus.BAD_REQUEST, null, null);
        }

        static RequestReadResult tooLarge() {
            return new RequestReadResult(RequestReadStatus.BODY_TOO_LARGE, null, null);
        }

        static RequestReadResult complete(HttpRequest request, VirtualServerConfig server) {
            return new RequestReadResult(RequestReadStatus.COMPLETE, request, server);
        }
    }

    private record ChunkedDecodeResult(RequestReadStatus status, byte[] body) {
        static ChunkedDecodeResult incomplete() {
            return new ChunkedDecodeResult(RequestReadStatus.INCOMPLETE, null);
        }

        static ChunkedDecodeResult bad() {
            return new ChunkedDecodeResult(RequestReadStatus.BAD_REQUEST, null);
        }

        static ChunkedDecodeResult tooLarge() {
            return new ChunkedDecodeResult(RequestReadStatus.BODY_TOO_LARGE, null);
        }

        static ChunkedDecodeResult complete(byte[] body) {
            return new ChunkedDecodeResult(RequestReadStatus.COMPLETE, body);
        }
    }
}
