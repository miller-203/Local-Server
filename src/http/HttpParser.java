package http;

import java.nio.charset.StandardCharsets;

public class HttpParser {
    public HttpRequest parse(String rawRequest) throws IllegalArgumentException {
        byte[] rawBytes = rawRequest.getBytes(StandardCharsets.ISO_8859_1);
        int headerEnd = findHeaderEnd(rawBytes);

        if (headerEnd == -1) {
            throw new IllegalArgumentException("Incomplete request headers");
        }

        byte[] headerBytes = new byte[headerEnd];
        System.arraycopy(rawBytes, 0, headerBytes, 0, headerEnd);

        int bodyStart = headerEnd + headerDelimiterLength(rawBytes, headerEnd);
        byte[] bodyBytes = new byte[Math.max(0, rawBytes.length - bodyStart)];

        if (bodyBytes.length > 0) {
            System.arraycopy(rawBytes, bodyStart, bodyBytes, 0, bodyBytes.length);
        }

        return parse(headerBytes, bodyBytes);
    }

    public HttpRequest parse(byte[] headerBytes, byte[] bodyBytes) throws IllegalArgumentException {
        HttpRequest request = new HttpRequest();

        String headerPart = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headerPart.split("\\r?\\n");

        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IllegalArgumentException("Empty request");
        }

        parseRequestLine(lines[0], request);
        parseHeaders(lines, request);

        request.setBodyBytes(bodyBytes);

        return request;
    }

    public HttpRequest parseHead(byte[] headerBytes) throws IllegalArgumentException {
        return parse(headerBytes, new byte[0]);
    }

    private void parseRequestLine(String requestLine, HttpRequest request) {
        String[] elements = requestLine.split(" ");

        if (elements.length != 3) {
            throw new IllegalArgumentException("Invalid request line");
        }

        String method = elements[0];
        String target = elements[1];
        String version = elements[2];

        if (!method.matches("[A-Z]+")) {
            throw new IllegalArgumentException("Invalid method");
        }

        if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
            throw new IllegalArgumentException("Unsupported HTTP version");
        }

        request.setMethod(method);
        parseTarget(target, request);
        request.setVersion(version);
    }

    private void parseTarget(String target, HttpRequest request) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Empty request target");
        }

        if (target.indexOf('\0') != -1) {
            throw new IllegalArgumentException("Invalid null byte in request target");
        }

        int questionMarkIndex = target.indexOf("?");

        if (questionMarkIndex == -1) {
            request.setPath(decodePath(target));
            request.setQueryString("");
            return;
        }

        String path = target.substring(0, questionMarkIndex);
        String queryString = target.substring(questionMarkIndex + 1);

        request.setPath(decodePath(path));
        request.setQueryString(queryString);
    }

    private void parseHeaders(String[] lines, HttpRequest request) {
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];

            if (line.isBlank()) {
                continue;
            }

            int colonIndex = line.indexOf(":");

            if (colonIndex == -1) {
                throw new IllegalArgumentException("Invalid header line");
            }

            String name = line.substring(0, colonIndex).trim();
            String value = line.substring(colonIndex + 1).trim();

            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty header name");
            }

            request.addHeader(name, value);
        }
    }

    private String decodePath(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Request path must start with /");
        }

        StringBuilder decoded = new StringBuilder();

        for (int i = 0; i < path.length(); i++) {
            char current = path.charAt(i);

            if (current == '%') {
                if (i + 2 >= path.length()) {
                    throw new IllegalArgumentException("Invalid percent encoding");
                }

                int high = Character.digit(path.charAt(i + 1), 16);
                int low = Character.digit(path.charAt(i + 2), 16);

                if (high == -1 || low == -1) {
                    throw new IllegalArgumentException("Invalid percent encoding");
                }

                decoded.append((char) ((high << 4) + low));
                i += 2;
                continue;
            }

            decoded.append(current);
        }

        return decoded.toString();
    }

    private int findHeaderEnd(byte[] bytes) {
        for (int i = 0; i < bytes.length - 3; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n'
                    && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i;
            }
        }

        for (int i = 0; i < bytes.length - 1; i++) {
            if (bytes[i] == '\n' && bytes[i + 1] == '\n') {
                return i;
            }
        }

        return -1;
    }

    private int headerDelimiterLength(byte[] bytes, int headerEnd) {
        if (headerEnd + 3 < bytes.length
                && bytes[headerEnd] == '\r'
                && bytes[headerEnd + 1] == '\n'
                && bytes[headerEnd + 2] == '\r'
                && bytes[headerEnd + 3] == '\n') {
            return 4;
        }

        return 2;
    }
}
