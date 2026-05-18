package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class ServerLogger {
    private final Path serverLog;
    private final Path requestLog;
    private final Path errorLog;

    public ServerLogger(String logDirectory) throws IOException {
        Path root = Path.of(logDirectory);
        Files.createDirectories(root);
        this.serverLog = root.resolve("server.log");
        this.requestLog = root.resolve("request.log");
        this.errorLog = root.resolve("error.log");
    }

    public void server(String message) {
        append(serverLog, message);
    }

    public void request(String remote, String method, String path, int statusCode, long durationMillis) {
        append(requestLog, "%s %s %s %d %dms".formatted(remote, method, path, statusCode, durationMillis));
    }

    public void error(String message, Throwable throwable) {
        String detail = throwable == null ? message : message + " - " + throwable.getMessage();
        append(errorLog, detail);
        System.err.println(detail);
    }

    private void append(Path file, String message) {
        try {
            Files.writeString(
                    file,
                    Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Log write failed: " + e.getMessage());
        }
    }
}
