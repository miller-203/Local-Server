package routing;

import config.RouteConfig;
import config.VirtualServerConfig;
import handlers.ErrorResponseFactory;
import handlers.StaticFileHandler;
import http.HttpRequest;
import http.HttpResponse;
import http.HttpStatus;
import utils.Metrics;
import utils.SessionManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Router {
    private final ErrorResponseFactory errors;
    private final StaticFileHandler handler;
    private final Metrics metrics;
    private final SessionManager sessions;

    public Router(Metrics metrics, SessionManager sessions) {
        this.errors = new ErrorResponseFactory();
        this.handler = new StaticFileHandler(errors);
        this.metrics = metrics;
        this.sessions = sessions;
    }

    public Router() {
        this(new Metrics(), new SessionManager());
    }

    public HttpResponse route(
            HttpRequest request,
            VirtualServerConfig server,
            SessionManager.SessionContext sessionContext) {
        trackSessionRequest(sessionContext);

        if ("/metrics".equals(request.getPath())) {
            HttpResponse response = new HttpResponse(
                    200,
                    "OK",
                    metrics.toJson(sessions.size()).getBytes(StandardCharsets.UTF_8));
            response.addHeader("Content-Type", "application/json; charset=UTF-8");
            return response;
        }

        List<Route> routes = routesFor(server);
        Route matchedRoute = findRoute(routes, request.getPath());

        if (matchedRoute == null) {
            return errors.create(server, 404);
        }

        if (matchedRoute.getRedirect() != null) {
            int status = matchedRoute.getRedirect().getStatusCode();
            HttpResponse response = new HttpResponse(status, HttpStatus.reasonPhrase(status), "");
            response.addHeader("Location", matchedRoute.getRedirect().getLocation());
            response.addHeader("Content-Type", "text/plain; charset=UTF-8");
            return response;
        }

        if (!matchedRoute.allowsMethod(request.getMethod())) {
            HttpResponse response = errors.create(server, 405);
            response.addHeader("Allow", String.join(", ", matchedRoute.getMethods()));
            return response;
        }

        String strippedPath = stripRoutePrefix(request.getPath(), matchedRoute.getPath());
        return handler.handle(request, matchedRoute, server, strippedPath);
    }

    private List<Route> routesFor(VirtualServerConfig server) {
        List<Route> routes = new ArrayList<>();

        for (RouteConfig routeConfig : server.getRoutes()) {
            routes.add(new Route(
                    routeConfig.getPath(),
                    routeConfig.getMethods(),
                    routeConfig.getRoot(),
                    routeConfig.getIndexFile(),
                    routeConfig.isDirectoryListingEnabled(),
                    routeConfig.getDirectoryDefaultFile(),
                    routeConfig.getUploadDirectory(),
                    routeConfig.getRedirect(),
                    routeConfig.getCgiHandlers()));
        }

        routes.sort(Comparator.comparingInt((Route r) -> r.getPath().length()).reversed());
        return routes;
    }

    private Route findRoute(List<Route> routes, String requestPath) {
        for (Route route : routes) {
            if (route.matches(requestPath)) {
                return route;
            }
        }

        return null;
    }

    private String stripRoutePrefix(String requestPath, String routePath) {
        if (routePath.equals("/")) {
            return requestPath;
        }

        if (requestPath.equals(routePath)) {
            return "/";
        }

        String stripped = requestPath.substring(routePath.length());

        if (stripped.isEmpty()) {
            return "/";
        }

        return stripped;
    }

    private void trackSessionRequest(SessionManager.SessionContext sessionContext) {
        String current = sessionContext.getSession().getData().getOrDefault("request_count", "0");

        try {
            int count = Integer.parseInt(current);
            sessionContext.getSession().getData().put("request_count", String.valueOf(count + 1));
        } catch (NumberFormatException e) {
            sessionContext.getSession().getData().put("request_count", "1");
        }
    }
}
