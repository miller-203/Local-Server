package handlers;

import config.VirtualServerConfig;
import http.HttpRequest;
import http.HttpResponse;
import http.HttpStatus;
import routing.Route;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CgiHandler {
    private static final Duration CGI_TIMEOUT = Duration.ofSeconds(5);

    public boolean isCgiRequest(Route route, String strippedPath) {
        return findMatch(route, strippedPath) != null;
    }

    public HttpResponse handle(
            HttpRequest request,
            Route route,
            String strippedPath,
            Path root,
            VirtualServerConfig server) throws IOException {
        CgiMatch match = findMatch(route, strippedPath);

        if (match == null) {
            throw new IOException("No CGI handler configured");
        }

        Path script = root.resolve(match.scriptRelativePath()).normalize();

        if (!script.startsWith(root) || !Files.isRegularFile(script) || !Files.isReadable(script)) {
            return html(404, "CGI script not found");
        }

        Path outputFile = Files.createTempFile("localserver-cgi-", ".out");

        try {
            ProcessBuilder builder = new ProcessBuilder(command(match.command(), script));
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());

            Map<String, String> env = builder.environment();
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("QUERY_STRING", request.getQueryString());
            env.put("CONTENT_LENGTH", String.valueOf(request.getBodyBytes().length));
            env.put("CONTENT_TYPE", valueOrEmpty(request.getHeader("Content-Type")));
            env.put("SERVER_PROTOCOL", request.getVersion());
            env.put("SERVER_NAME", valueOrEmpty(request.getHeader("Host")));
            env.put("SERVER_PORT", String.valueOf(request.getLocalPort()));
            env.put("REMOTE_ADDR", request.getRemoteAddress());
            env.put("SCRIPT_NAME", route.getPath() + "/" + match.scriptRelativePath());
            env.put("SCRIPT_FILENAME", script.toString());
            env.put("PATH_INFO", match.pathInfo());
            env.put("PATH_TRANSLATED", translatedPath(root, match.pathInfo()).toString());
            env.put("REQUEST_URI", request.getPath()
                    + (request.getQueryString().isBlank() ? "" : "?" + request.getQueryString()));
            env.put("HTTP_COOKIE", valueOrEmpty(request.getHeader("Cookie")));

            Process process = builder.start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(request.getBodyBytes());
            }

            boolean finished;

            try {
                finished = process.waitFor(CGI_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }

            if (!finished) {
                process.destroyForcibly();
                return html(500, "CGI timeout");
            }

            byte[] output = Files.readAllBytes(outputFile);

            if (process.exitValue() != 0 && output.length == 0) {
                return html(500, "CGI failed");
            }

            return parseCgiOutput(output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private CgiMatch findMatch(Route route, String strippedPath) {
        String cleanPath = strippedPath.startsWith("/") ? strippedPath.substring(1) : strippedPath;

        for (Map.Entry<String, String> handler : route.getCgiHandlers().entrySet()) {
            String extension = handler.getKey();
            int extensionIndex = cleanPath.indexOf(extension);

            if (extensionIndex == -1) {
                continue;
            }

            int scriptEnd = extensionIndex + extension.length();

            if (scriptEnd < cleanPath.length() && cleanPath.charAt(scriptEnd) != '/') {
                continue;
            }

            String script = cleanPath.substring(0, scriptEnd);
            String pathInfo = cleanPath.substring(scriptEnd);

            if (pathInfo.isEmpty()) {
                pathInfo = "/";
            }

            return new CgiMatch(script, pathInfo, handler.getValue());
        }

        return null;
    }

    private List<String> command(String configuredCommand, Path script) {
        List<String> command = new ArrayList<>();

        for (String part : configuredCommand.split("\\s+")) {
            if (!part.isBlank()) {
                command.add(part);
            }
        }

        command.add(script.toString());
        return command;
    }

    private HttpResponse parseCgiOutput(byte[] output) {
        String text = new String(output, StandardCharsets.ISO_8859_1);
        int separator = text.indexOf("\r\n\r\n");
        int separatorLength = 4;

        if (separator == -1) {
            separator = text.indexOf("\n\n");
            separatorLength = 2;
        }

        if (separator == -1) {
            HttpResponse response = new HttpResponse(200, "OK", output);
            response.addHeader("Content-Type", "text/html; charset=UTF-8");
            return response;
        }

        String headerText = text.substring(0, separator);
        byte[] body = text.substring(separator + separatorLength).getBytes(StandardCharsets.ISO_8859_1);
        int status = 200;
        String reason = "OK";
        Map<String, String> headers = new LinkedHashMap<>();

        for (String line : headerText.split("\\r?\\n")) {
            int colon = line.indexOf(':');

            if (colon == -1) {
                continue;
            }

            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();

            if ("Status".equalsIgnoreCase(name)) {
                String[] parts = value.split(" ", 2);
                status = Integer.parseInt(parts[0]);
                reason = parts.length > 1 ? parts[1] : HttpStatus.reasonPhrase(status);
                continue;
            }

            headers.put(name, value);
        }

        HttpResponse response = new HttpResponse(status, reason, body);

        for (Map.Entry<String, String> header : headers.entrySet()) {
            response.addHeader(header.getKey(), header.getValue());
        }

        if (!response.getHeaders().containsKey("Content-Type")) {
            response.addHeader("Content-Type", "text/html; charset=UTF-8");
        }

        return response;
    }

    private HttpResponse html(int status, String message) {
        HttpResponse response = new HttpResponse(status, HttpStatus.reasonPhrase(status), """
                <!DOCTYPE html>
                <html><body><h1>%s</h1></body></html>
                """.formatted(message));
        response.addHeader("Content-Type", "text/html; charset=UTF-8");
        return response;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private Path translatedPath(Path root, String pathInfo) {
        String relativePath = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return root.resolve(relativePath).normalize();
    }

    private record CgiMatch(String scriptRelativePath, String pathInfo, String command) {
    }
}
