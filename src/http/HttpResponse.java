package http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponse {
    private String version;
    private int statusCode;
    private String reasonPhrase;
    private Map<String, String> headers;
    private byte[] body;

    public HttpResponse(int statusCode, String reasonPhrase, String bodyText) {
        this(statusCode, reasonPhrase, bodyText.getBytes(StandardCharsets.UTF_8));
    }

    public HttpResponse(int statusCode, String reasonPhrase, byte[] body) {
        this.version = "HTTP/1.1";
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = new LinkedHashMap<>();
        this.body = body;

        addHeader("Content-Length", String.valueOf(this.body.length));
        addHeader("Connection", "close");
    }

    public void addHeader(String name, String value) {
        this.headers.put(name, value);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public byte[] getBody() {
        return body.clone();
    }

    public void setBody(byte[] body) {
        this.body = body == null ? new byte[0] : body.clone();
        addHeader("Content-Length", String.valueOf(this.body.length));
    }

    public byte[] toBytes() {
        StringBuilder response = new StringBuilder();

        response.append(version)
                .append(" ")
                .append(statusCode)
                .append(" ")
                .append(reasonPhrase)
                .append("\r\n");

        for (Map.Entry<String, String> header : headers.entrySet()) {
            response.append(header.getKey())
                    .append(": ")
                    .append(header.getValue())
                    .append("\r\n");
        }

        response.append("\r\n");

        byte[] headerBytes = response.toString().getBytes(StandardCharsets.UTF_8);

        byte[] fullResponse = new byte[headerBytes.length + body.length];

        System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
        System.arraycopy(body, 0, fullResponse, headerBytes.length, body.length);

        return fullResponse;
    }
}
