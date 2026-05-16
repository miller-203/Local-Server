package http;

public class HttpParser {
    public HttpRequest parse(String rawRequest) throws IllegalArgumentException {
        HttpRequest request = new HttpRequest();

        String[] parts = rawRequest.split("\\r?\\n\\r?\\n", 2);

        String headerPart = parts[0];
        String bodyPart = "";

        if (parts.length > 1) {
            bodyPart = parts[1];
        }

        String[] lines = headerPart.split("\\r?\\n");

        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IllegalArgumentException("Empty request");
        }

        parseRequestLine(lines[0], request);
        parseHeaders(lines, request);

        request.setBody(bodyPart);

        return request;
    }

    private void parseRequestLine(String requestLine, HttpRequest request) {
        String[] elements = requestLine.split(" ");

        if (elements.length != 3) {
            throw new IllegalArgumentException("Invalid request line");
        }

        String method = elements[0];
        String target = elements[1];
        String version = elements[2];

        request.setMethod(method);
        parseTarget(target, request);
        request.setVersion(version);
    }

    private void parseTarget(String target, HttpRequest request) {
        int questionMarkIndex = target.indexOf("?");

        if (questionMarkIndex == -1) {
            request.setPath(target);
            request.setQueryString("");
            return;
        }

        String path = target.substring(0, questionMarkIndex);
        String queryString = target.substring(questionMarkIndex + 1);

        request.setPath(path);
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
}