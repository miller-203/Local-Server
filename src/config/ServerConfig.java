package config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ServerConfig {
    private final List<VirtualServerConfig> servers;
    private final int clientTimeoutMillis;

    public ServerConfig(String host, List<Integer> ports, List<RouteConfig> routes) {
        this(
                List.of(new VirtualServerConfig(
                        host,
                        ports,
                        Set.of(host.toLowerCase(), "localhost"),
                        routes,
                        java.util.Map.of(),
                        1_048_576L,
                        "./uploads")),
                10_000);
    }

    public ServerConfig(List<VirtualServerConfig> servers, int clientTimeoutMillis) {
        this.servers = servers;
        this.clientTimeoutMillis = clientTimeoutMillis;
    }

    public String getHost() {
        return servers.get(0).getHost();
    }

    public List<Integer> getPorts() {
        return servers.get(0).getPorts();
    }

    public List<RouteConfig> getRoutes() {
        return servers.get(0).getRoutes();
    }

    public List<VirtualServerConfig> getServers() {
        return servers;
    }

    public int getClientTimeoutMillis() {
        return clientTimeoutMillis;
    }

    public Set<ListenAddress> getListenAddresses() {
        Set<ListenAddress> addresses = new LinkedHashSet<>();

        for (VirtualServerConfig server : servers) {
            for (int port : server.getPorts()) {
                addresses.add(new ListenAddress(server.getHost(), port));
            }
        }

        return addresses;
    }

    public List<VirtualServerConfig> findServers(String listenHost, int port) {
        List<VirtualServerConfig> matches = new ArrayList<>();

        for (VirtualServerConfig server : servers) {
            if (server.listensOn(listenHost, port)) {
                matches.add(server);
            }
        }

        return matches;
    }

    public VirtualServerConfig findVirtualServer(String listenHost, int port, String hostHeader) {
        List<VirtualServerConfig> matches = findServers(listenHost, port);

        for (VirtualServerConfig server : matches) {
            if (server.matchesHostHeader(hostHeader)) {
                return server;
            }
        }

        if (matches.isEmpty()) {
            return servers.get(0);
        }

        return matches.get(0);
    }

    public record ListenAddress(String host, int port) {
    }
}
