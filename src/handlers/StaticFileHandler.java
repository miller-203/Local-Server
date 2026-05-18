package handlers;

import config.VirtualServerConfig;
import http.HttpRequest;
import http.HttpResponse;
import routing.Route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StaticFileHandler {
    private final ErrorResponseFactory errors;
    private final CgiHandler cgiHandler;

    public StaticFileHandler(ErrorResponseFactory errors) {
        this.errors = errors;
        this.cgiHandler = new CgiHandler();
    }

    public HttpResponse handle(HttpRequest request, Route route, VirtualServerConfig server, String strippedPath) {
        try {
            Path root = Path.of(route.getRoot()).toRealPath();

            if (!route.getCgiHandlers().isEmpty() && cgiHandler.isCgiRequest(route, strippedPath)) {
                return cgiHandler.handle(request, route, strippedPath, root, server);
            }

            return switch (request.getMethod()) {
                case "GET" -> handleGet(request, route, server, strippedPath, root);
                case "POST" -> handlePost(request, route, server, root);
                case "DELETE" -> handleDelete(route, server, strippedPath, root);
                default -> errors.create(server, 405);
            };
        } catch (SecurityException e) {
            return errors.create(server, 403);
        } catch (IOException e) {
            return errors.create(server, 500);
        }
    }

    private HttpResponse handleGet(
            HttpRequest request,
            Route route,
            VirtualServerConfig server,
            String strippedPath,
            Path root) throws IOException {
        Path filePath = resolvePath(root, strippedPath);

        if (!isInsideRoot(root, filePath)) {
            return errors.create(server, 403);
        }

        if (!Files.exists(filePath)) {
            return errors.create(server, 404);
        }

        if (Files.isDirectory(filePath)) {
            HttpResponse directoryResponse = handleDirectory(request, route, server, root, filePath);

            if (directoryResponse != null) {
                return directoryResponse;
            }
        }

        if (!Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
            return errors.create(server, 403);
        }

        byte[] fileBytes = Files.readAllBytes(filePath);

        HttpResponse response = new HttpResponse(200, "OK", fileBytes);
        response.addHeader("Content-Type", detectContentType(filePath));
        return response;
    }

    private HttpResponse handleDirectory(
            HttpRequest request,
            Route route,
            VirtualServerConfig server,
            Path root,
            Path directory) throws IOException {
        Path indexPath = directory.resolve(route.getIndexFile()).normalize();

        if (isInsideRoot(root, indexPath) && Files.isRegularFile(indexPath) && Files.isReadable(indexPath)) {
            byte[] fileBytes = Files.readAllBytes(indexPath);
            HttpResponse response = new HttpResponse(200, "OK", fileBytes);
            response.addHeader("Content-Type", detectContentType(indexPath));
            return response;
        }

        String defaultFile = route.getDirectoryDefaultFile();

        if (defaultFile != null && !defaultFile.isBlank()) {
            Path defaultPath = directory.resolve(defaultFile).normalize();

            if (isInsideRoot(root, defaultPath) && Files.isRegularFile(defaultPath) && Files.isReadable(defaultPath)) {
                byte[] fileBytes = Files.readAllBytes(defaultPath);
                HttpResponse response = new HttpResponse(200, "OK", fileBytes);
                response.addHeader("Content-Type", detectContentType(defaultPath));
                return response;
            }
        }

        if (!route.isDirectoryListingEnabled()) {
            return errors.create(server, 403);
        }

        return directoryListing(request, directory);
    }

    private HttpResponse handlePost(
            HttpRequest request,
            Route route,
            VirtualServerConfig server,
            Path root) throws IOException {
        Path uploadDirectory = uploadDirectory(route, server, root);
        Files.createDirectories(uploadDirectory);

        if (!Files.isDirectory(uploadDirectory) || !Files.isWritable(uploadDirectory)) {
            return errors.create(server, 403);
        }

        List<Path> savedFiles = saveUploads(request, uploadDirectory);
        String body = """
                {
                  "uploaded": %d
                }
                """.formatted(savedFiles.size());

        HttpResponse response = new HttpResponse(201, "Created", body);
        response.addHeader("Content-Type", "application/json; charset=UTF-8");
        return response;
    }

    private HttpResponse handleDelete(
            Route route,
            VirtualServerConfig server,
            String strippedPath,
            Path root) throws IOException {
        Path filePath = resolvePath(root, strippedPath);

        if (!isInsideRoot(root, filePath)) {
            return errors.create(server, 403);
        }

        if (!Files.exists(filePath)) {
            return errors.create(server, 404);
        }

        if (!Files.isRegularFile(filePath) || !Files.isWritable(filePath)) {
            return errors.create(server, 403);
        }

        Files.delete(filePath);
        HttpResponse response = new HttpResponse(204, "No Content", new byte[0]);
        response.addHeader("Content-Type", "text/plain; charset=UTF-8");
        return response;
    }

    private List<Path> saveUploads(HttpRequest request, Path uploadDirectory) throws IOException {
        String contentType = request.getHeader("Content-Type");

        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            String boundary = boundaryFromContentType(contentType);

            if (boundary != null) {
                return saveMultipartUploads(request.getBodyBytes(), boundary, uploadDirectory);
            }
        }

        String fileName = safeFileName(request.getHeader("X-Filename"));

        if (fileName == null) {
            fileName = "upload-" + System.currentTimeMillis() + ".bin";
        }

        Path destination = uniqueDestination(uploadDirectory.resolve(fileName).normalize());

        if (!destination.startsWith(uploadDirectory.normalize())) {
            throw new SecurityException("Invalid upload path");
        }

        Files.write(destination, request.getBodyBytes(), StandardOpenOption.CREATE_NEW);
        return List.of(destination);
    }

    private List<Path> saveMultipartUploads(byte[] body, String boundary, Path uploadDirectory) throws IOException {
        List<Path> saved = new ArrayList<>();
        String payload = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        String[] parts = payload.split(java.util.regex.Pattern.quote(delimiter));

        for (String part : parts) {
            if (part.isBlank() || part.startsWith("--")) {
                continue;
            }

            if (part.startsWith("\r\n")) {
                part = part.substring(2);
            } else if (part.startsWith("\n")) {
                part = part.substring(1);
            }

            int separator = part.indexOf("\r\n\r\n");
            int separatorLength = 4;

            if (separator == -1) {
                separator = part.indexOf("\n\n");
                separatorLength = 2;
            }

            if (separator == -1) {
                continue;
            }

            String headerText = part.substring(0, separator);
            String fileName = fileNameFromPartHeaders(headerText);

            if (fileName == null) {
                continue;
            }

            String fileContent = part.substring(separator + separatorLength);

            if (fileContent.endsWith("\r\n")) {
                fileContent = fileContent.substring(0, fileContent.length() - 2);
            } else if (fileContent.endsWith("\n")) {
                fileContent = fileContent.substring(0, fileContent.length() - 1);
            }

            Path destination = uniqueDestination(uploadDirectory.resolve(fileName).normalize());

            if (!destination.startsWith(uploadDirectory.normalize())) {
                throw new SecurityException("Invalid upload path");
            }

            Files.write(destination, fileContent.getBytes(StandardCharsets.ISO_8859_1), StandardOpenOption.CREATE_NEW);
            saved.add(destination);
        }

        return saved;
    }

    private String boundaryFromContentType(String contentType) {
        for (String piece : contentType.split(";")) {
            String trimmed = piece.trim();

            if (trimmed.startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length());

                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }

                return boundary;
            }
        }

        return null;
    }

    private String fileNameFromPartHeaders(String headers) {
        for (String line : headers.split("\\r?\\n")) {
            if (!line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) {
                continue;
            }

            for (String attribute : line.split(";")) {
                String trimmed = attribute.trim();

                if (trimmed.startsWith("filename=")) {
                    String value = trimmed.substring("filename=".length());

                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }

                    return safeFileName(value);
                }
            }
        }

        return null;
    }

    private Path uploadDirectory(Route route, VirtualServerConfig server, Path root) {
        if (route.getUploadDirectory() != null && !route.getUploadDirectory().isBlank()) {
            return Path.of(route.getUploadDirectory()).toAbsolutePath().normalize();
        }

        if (server.getUploadDirectory() != null && !server.getUploadDirectory().isBlank()) {
            return Path.of(server.getUploadDirectory()).toAbsolutePath().normalize();
        }

        return root.resolve("uploads").normalize();
    }

    private Path uniqueDestination(Path destination) throws IOException {
        if (!Files.exists(destination)) {
            return destination;
        }

        String fileName = destination.getFileName().toString();
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');

        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        for (int i = 1; i < 10_000; i++) {
            Path candidate = destination.resolveSibling(base + "-" + i + extension);

            if (!Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IOException("Could not create unique upload path");
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        String clean = Path.of(fileName).getFileName().toString();
        clean = clean.replaceAll("[^A-Za-z0-9._-]", "_");

        if (clean.isBlank() || ".".equals(clean) || "..".equals(clean)) {
            return null;
        }

        return clean;
    }

    private HttpResponse directoryListing(HttpRequest request, Path directory) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("""
                <!DOCTYPE html>
                <html>
                <head><title>Directory listing</title></head>
                <body><h1>Directory listing</h1><ul>
                """);

        try (var stream = Files.list(directory)) {
            stream.sorted().forEach(path -> {
                String name = path.getFileName().toString();
                String href = request.getPath();

                if (!href.endsWith("/")) {
                    href += "/";
                }

                body.append("<li><a href=\"")
                        .append(escapeHtml(href + name))
                        .append("\">")
                        .append(escapeHtml(name))
                        .append("</a></li>");
            });
        }

        body.append("</ul></body></html>");

        HttpResponse response = new HttpResponse(200, "OK", body.toString());
        response.addHeader("Content-Type", "text/html; charset=UTF-8");
        return response;
    }

    private Path resolvePath(Path root, String requestPath) {
        String cleanPath = requestPath;

        if (cleanPath == null || cleanPath.isBlank() || cleanPath.equals("/")) {
            cleanPath = "";
        }

        if (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }

        return root.resolve(cleanPath).normalize();
    }

    private boolean isInsideRoot(Path root, Path filePath) {
        return filePath.normalize().startsWith(root.normalize());
    }

    private String detectContentType(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "text/html; charset=UTF-8";
        }

        if (fileName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }

        if (fileName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }

        if (fileName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }

        if (fileName.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        }

        if (fileName.endsWith(".png")) {
            return "image/png";
        }

        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }

        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }

        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }

        return "application/octet-stream";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
