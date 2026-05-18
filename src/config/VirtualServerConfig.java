package config;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class VirtualServerConfig {
    private final String host;
    private final List<Integer> ports;
    private final Set<String> serverNames;
    private final List<RouteConfig> routes;
    private final Map<Integer, String> errorPages;
    private final long clientMaxBodySize;
    private final String uploadDirectory;

    public VirtualServerConfig(
            String host,
            List<Integer> ports,
            Set<String> serverNames,
            List<RouteConfig> routes,
            Map<Integer, String> errorPages,
            long clientMaxBodySize,
            String uploadDirectory) {
        this.host = host;
        this.ports = ports;
        this.serverNames = serverNames;
        this.routes = routes;
        this.errorPages = errorPages;
        this.clientMaxBodySize = clientMaxBodySize;
        this.uploadDirectory = uploadDirectory;
    }

    public String getHost() {
        return host;
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public Set<String> getServerNames() {
        return serverNames;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    public Map<Integer, String> getErrorPages() {
        return errorPages;
    }

    public long getClientMaxBodySize() {
        return clientMaxBodySize;
    }

    public String getUploadDirectory() {
        return uploadDirectory;
    }

    public boolean listensOn(String listenHost, int port) {
        return host.equals(listenHost) && ports.contains(port);
    }

    public boolean matchesHostHeader(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }

        String normalized = hostHeader.toLowerCase();
        int colon = normalized.indexOf(':');

        if (colon != -1) {
            normalized = normalized.substring(0, colon);
        }

        return serverNames.contains(normalized) || host.equals(normalized);
    }
}
