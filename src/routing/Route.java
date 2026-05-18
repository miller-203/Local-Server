package routing;

import config.RedirectConfig;

import java.util.Map;
import java.util.Set;

public class Route {
    private final String path;
    private final Set<String> methods;
    private final String root;
    private final String indexFile;
    private final boolean directoryListing;
    private final String directoryDefaultFile;
    private final String uploadDirectory;
    private final RedirectConfig redirect;
    private final Map<String, String> cgiHandlers;

    public Route(String path, Set<String> methods, String root, String indexFile) {
        this(path, methods, root, indexFile, false, null, null, null, Map.of());
    }

    public Route(
            String path,
            Set<String> methods,
            String root,
            String indexFile,
            boolean directoryListing,
            String directoryDefaultFile,
            String uploadDirectory,
            RedirectConfig redirect,
            Map<String, String> cgiHandlers) {
        this.path = normalizeRoutePath(path);
        this.methods = methods;
        this.root = root;
        this.indexFile = indexFile;
        this.directoryListing = directoryListing;
        this.directoryDefaultFile = directoryDefaultFile;
        this.uploadDirectory = uploadDirectory;
        this.redirect = redirect;
        this.cgiHandlers = cgiHandlers;
    }

    public String getPath() {
        return path;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public String getRoot() {
        return root;
    }

    public String getIndexFile() {
        return indexFile;
    }

    public boolean isDirectoryListingEnabled() {
        return directoryListing;
    }

    public String getDirectoryDefaultFile() {
        return directoryDefaultFile;
    }

    public String getUploadDirectory() {
        return uploadDirectory;
    }

    public RedirectConfig getRedirect() {
        return redirect;
    }

    public Map<String, String> getCgiHandlers() {
        return cgiHandlers;
    }

    public boolean allowsMethod(String method) {
        return methods.contains(method);
    }

    public boolean matches(String requestPath) {
        if (path.equals("/")) {
            return true;
        }

        return requestPath.equals(path) || requestPath.startsWith(path + "/");
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
}
