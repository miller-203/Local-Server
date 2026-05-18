package handlers;

import config.VirtualServerConfig;
import http.HttpResponse;
import http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public class ErrorResponseFactory {
    public HttpResponse create(VirtualServerConfig server, int statusCode) {
        return create(server, statusCode, HttpStatus.reasonPhrase(statusCode));
    }

    public HttpResponse create(VirtualServerConfig server, int statusCode, String message) {
        byte[] body = loadCustomErrorPage(server, statusCode);

        if (body == null) {
            body = defaultBody(statusCode, message).getBytes(StandardCharsets.UTF_8);
        }

        HttpResponse response = new HttpResponse(statusCode, HttpStatus.reasonPhrase(statusCode), body);
        response.addHeader("Content-Type", "text/html; charset=UTF-8");
        return response;
    }

    private byte[] loadCustomErrorPage(VirtualServerConfig server, int statusCode) {
        if (server == null || !server.getErrorPages().containsKey(statusCode)) {
            return null;
        }

        try {
            Path errorPage = Path.of(server.getErrorPages().get(statusCode)).normalize();

            if (Files.isRegularFile(errorPage) && Files.isReadable(errorPage)) {
                return Files.readAllBytes(errorPage);
            }
        } catch (IOException ignored) {
            return null;
        }

        return null;
    }

    private String defaultBody(int statusCode, String message) {
        return """
                <!DOCTYPE html>
                <html>
                <head><title>%d %s</title></head>
                <body><h1>%d %s</h1></body>
                </html>
                """.formatted(statusCode, message, statusCode, message);
    }
}
