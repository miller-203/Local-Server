package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConfigLoader {
    private static final long DEFAULT_CLIENT_MAX_BODY_SIZE = 1_048_576L;
    private static final int DEFAULT_CLIENT_TIMEOUT_MILLIS = 10_000;

    public ServerConfig load(String configPath) throws IOException {
        String json = Files.readString(Path.of(configPath));
        Object parsed = SimpleJsonParser.parse(json);

        if (!(parsed instanceof Map<?, ?> rootObject)) {
            throw new IllegalArgumentException("Config root must be a JSON object");
        }

        Map<String, Object> root = castObject(rootObject);
        int timeout = getInt(root, "client_timeout_ms", DEFAULT_CLIENT_TIMEOUT_MILLIS);
        String logDirectory = getString(root, "log_directory", "./logs");

        List<VirtualServerConfig> servers;

        if (root.containsKey("servers")) {
            servers = parseServers(root);
        } else {
            servers = List.of(parseServer(root, root));
        }

        return new ServerConfig(servers, timeout, logDirectory);
    }

    private List<VirtualServerConfig> parseServers(Map<String, Object> root) {
        List<Object> rawServers = getObjectList(root, "servers");
        List<VirtualServerConfig> servers = new ArrayList<>();

        for (int i = 0; i < rawServers.size(); i++) {
            try {
                servers.add(parseServer(castObject(rawServers.get(i)), root));
            } catch (IllegalArgumentException e) {
                System.err.println("Ignoring invalid server config #" + (i + 1) + ": " + e.getMessage());
            }
        }

        if (servers.isEmpty()) {
            throw new IllegalArgumentException("At least one valid server block is required");
        }

        return servers;
    }

    private VirtualServerConfig parseServer(Map<String, Object> server, Map<String, Object> root) {
        String host = getString(server, "host", getString(root, "host", "127.0.0.1"));
        List<Integer> ports = parsePorts(server);
        Set<String> serverNames = parseServerNames(server, host);
        Map<Integer, String> errorPages = parseErrorPages(root);
        errorPages.putAll(parseErrorPages(server));

        long clientMaxBodySize = getLong(
                server,
                "client_max_body_size",
                getLong(root, "client_max_body_size", DEFAULT_CLIENT_MAX_BODY_SIZE));
        String uploadDirectory = getString(server, "upload_dir", getString(root, "upload_dir", "./uploads"));
        List<RouteConfig> routes = parseRoutes(server);

        validateServer(host, ports, routes, clientMaxBodySize);

        return new VirtualServerConfig(
                host.toLowerCase(Locale.ROOT),
                ports,
                serverNames,
                routes,
                errorPages,
                clientMaxBodySize,
                uploadDirectory);
    }

    private List<Integer> parsePorts(Map<String, Object> server) {
        List<Integer> ports = new ArrayList<>();

        if (server.containsKey("ports")) {
            for (Object value : getObjectList(server, "ports")) {
                ports.add(toInt(value, "ports"));
            }
        } else if (server.containsKey("port")) {
            ports.add(toInt(server.get("port"), "port"));
        } else {
            throw new IllegalArgumentException("Missing ports");
        }

        Set<Integer> seen = new HashSet<>();

        for (int port : ports) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535: " + port);
            }

            if (!seen.add(port)) {
                throw new IllegalArgumentException("Duplicate port in server block: " + port);
            }
        }

        return ports;
    }

    private Set<String> parseServerNames(Map<String, Object> server, String host) {
        Set<String> names = new LinkedHashSet<>();
        names.add(host.toLowerCase(Locale.ROOT));

        if (server.containsKey("server_name")) {
            names.add(getString(server, "server_name", host).toLowerCase(Locale.ROOT));
        }

        if (server.containsKey("server_names")) {
            for (Object value : getObjectList(server, "server_names")) {
                names.add(asString(value, "server_names").toLowerCase(Locale.ROOT));
            }
        }

        names.add("localhost");
        return names;
    }

    private List<RouteConfig> parseRoutes(Map<String, Object> server) {
        List<Object> rawRoutes = getObjectList(server, "routes");
        List<RouteConfig> routes = new ArrayList<>();

        for (Object rawRoute : rawRoutes) {
            Map<String, Object> route = castObject(rawRoute);
            String path = normalizeRoutePath(getString(route, "path", "/"));
            RedirectConfig redirect = parseRedirect(route);
            String root = getString(route, "root", redirect == null ? null : ".");
            Set<String> methods = parseMethods(route);
            String index = getString(route, "index", getString(route, "index_file", "index.html"));
            boolean directoryListing = getBoolean(route, "directory_listing", getBoolean(route, "autoindex", false));
            String directoryDefaultFile = getString(route, "directory_default_file",
                    getString(route, "default_directory_response", null));
            String uploadDirectory = getString(route, "upload_dir", null);
            Map<String, String> cgiHandlers = parseCgiHandlers(route);

            if ((root == null || root.isBlank()) && redirect == null) {
                throw new IllegalArgumentException("Route " + path + " root cannot be empty");
            }

            routes.add(new RouteConfig(
                    path,
                    methods,
                    root,
                    index,
                    directoryListing,
                    directoryDefaultFile,
                    uploadDirectory,
                    redirect,
                    cgiHandlers));
        }

        if (routes.isEmpty()) {
            throw new IllegalArgumentException("At least one route is required");
        }

        return routes;
    }

    private Set<String> parseMethods(Map<String, Object> route) {
        Set<String> methods = new LinkedHashSet<>();

        if (!route.containsKey("methods")) {
            methods.add("GET");
            return methods;
        }

        for (Object value : getObjectList(route, "methods")) {
            String method = asString(value, "methods").toUpperCase(Locale.ROOT);

            if (!Set.of("GET", "POST", "DELETE").contains(method)) {
                throw new IllegalArgumentException("Unsupported route method: " + method);
            }

            methods.add(method);
        }

        if (methods.isEmpty()) {
            throw new IllegalArgumentException("Route methods cannot be empty");
        }

        return methods;
    }

    private RedirectConfig parseRedirect(Map<String, Object> route) {
        if (route.containsKey("redirect")) {
            Map<String, Object> redirect = castObject(route.get("redirect"));
            int status = getInt(redirect, "status", 302);
            String location = getString(redirect, "to", getString(redirect, "location", null));
            validateRedirect(status, location);
            return new RedirectConfig(status, location);
        }

        if (route.containsKey("redirect_to")) {
            int status = getInt(route, "redirect_code", 302);
            String location = getString(route, "redirect_to", null);
            validateRedirect(status, location);
            return new RedirectConfig(status, location);
        }

        return null;
    }

    private void validateRedirect(int status, String location) {
        if (status != 301 && status != 302) {
            throw new IllegalArgumentException("Redirect status must be 301 or 302");
        }

        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Redirect location cannot be empty");
        }
    }

    private Map<String, String> parseCgiHandlers(Map<String, Object> route) {
        Map<String, String> handlers = new LinkedHashMap<>();

        Object raw = route.getOrDefault("cgi", route.get("cgi_extensions"));

        if (raw == null) {
            return handlers;
        }

        Map<String, Object> cgi = castObject(raw);

        for (Map.Entry<String, Object> entry : cgi.entrySet()) {
            String extension = entry.getKey().startsWith(".") ? entry.getKey() : "." + entry.getKey();
            handlers.put(extension, asString(entry.getValue(), "cgi." + entry.getKey()));
        }

        return handlers;
    }

    private Map<Integer, String> parseErrorPages(Map<String, Object> source) {
        Map<Integer, String> errorPages = new HashMap<>();
        Object raw = source.get("error_pages");

        if (raw == null) {
            return errorPages;
        }

        Map<String, Object> map = castObject(raw);

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            int status = Integer.parseInt(entry.getKey());
            errorPages.put(status, asString(entry.getValue(), "error_pages." + entry.getKey()));
        }

        return errorPages;
    }

    private void validateServer(String host, List<Integer> ports, List<RouteConfig> routes, long bodyLimit) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be empty");
        }

        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("At least one port is required");
        }

        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("At least one route is required");
        }

        if (bodyLimit < 0) {
            throw new IllegalArgumentException("Client body limit cannot be negative");
        }
    }

    private String normalizeRoutePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }

        return (Map<String, Object>) map;
    }

    private List<Object> getObjectList(Map<String, Object> object, String key) {
        Object value = object.get(key);

        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Missing array key: " + key);
        }

        return new ArrayList<>(list);
    }

    private String getString(Map<String, Object> object, String key, String defaultValue) {
        Object value = object.get(key);

        if (value == null) {
            return defaultValue;
        }

        return asString(value, key);
    }

    private String asString(Object value, String key) {
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("Expected string for key: " + key);
        }

        return stringValue;
    }

    private int getInt(Map<String, Object> object, String key, int defaultValue) {
        Object value = object.get(key);

        if (value == null) {
            return defaultValue;
        }

        return toInt(value, key);
    }

    private int toInt(Object value, String key) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected integer for key: " + key);
        }

        return number.intValue();
    }

    private long getLong(Map<String, Object> object, String key, long defaultValue) {
        Object value = object.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected integer for key: " + key);
        }

        return number.longValue();
    }

    private boolean getBoolean(Map<String, Object> object, String key, boolean defaultValue) {
        Object value = object.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Expected boolean for key: " + key);
        }

        return booleanValue;
    }
}
