package http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpRequest {
    private String method;
    private String path;
    private String queryString;
    private String version;
    private final Map<String, String> headers;
    private final Map<String, String> cookies;
    private byte[] body;
    private int localPort;
    private String remoteAddress;

    public HttpRequest() {
        this.headers = new HashMap<>();
        this.cookies = new LinkedHashMap<>();
        this.body = new byte[0];
        this.queryString = "";
        this.remoteAddress = "";
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getQueryString() {
        return queryString;
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }

    public void addHeader(String name, String value) {
        String normalizedName = name.toLowerCase();
        this.headers.put(normalizedName, value);

        if ("cookie".equals(normalizedName)) {
            parseCookies(value);
        }
    }

    public String getHeader(String name) {
        return this.headers.get(name.toLowerCase());
    }

    public Map<String, String> getCookies() {
        return Collections.unmodifiableMap(cookies);
    }

    public String getCookie(String name) {
        return cookies.get(name);
    }

    public String getBody() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public void setBody(String body) {
        this.body = body.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] getBodyBytes() {
        return body.clone();
    }

    public void setBodyBytes(byte[] body) {
        if (body == null) {
            this.body = new byte[0];
            return;
        }

        this.body = body.clone();
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress == null ? "" : remoteAddress;
    }

    public HttpRequest copyWithPath(String newPath) {
        HttpRequest copy = new HttpRequest();

        copy.setMethod(this.method);
        copy.setPath(newPath);
        copy.setQueryString(this.queryString);
        copy.setVersion(this.version);
        copy.setBodyBytes(this.body);
        copy.setLocalPort(this.localPort);
        copy.setRemoteAddress(this.remoteAddress);

        for (Map.Entry<String, String> header : this.headers.entrySet()) {
            copy.addHeader(header.getKey(), header.getValue());
        }

        return copy;
    }

    private void parseCookies(String cookieHeader) {
        cookies.clear();

        if (cookieHeader == null || cookieHeader.isBlank()) {
            return;
        }

        String[] pairs = cookieHeader.split(";");

        for (String pair : pairs) {
            int equalsIndex = pair.indexOf('=');

            if (equalsIndex <= 0) {
                continue;
            }

            String name = pair.substring(0, equalsIndex).trim();
            String value = pair.substring(equalsIndex + 1).trim();

            if (!name.isEmpty()) {
                cookies.put(name, value);
            }
        }
    }
}
