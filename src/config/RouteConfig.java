package config;

import java.util.Map;
import java.util.Set;

public class RouteConfig {
    private final String path;
    private final Set<String> methods;
    private final String root;
    private final String indexFile;
    private final boolean directoryListing;
    private final String directoryDefaultFile;
    private final String uploadDirectory;
    private final RedirectConfig redirect;
    private final Map<String, String> cgiHandlers;

    public RouteConfig(String path, Set<String> methods, String root, String indexFile) {
        this(path, methods, root, indexFile, false, null, null, null, Map.of());
    }

    public RouteConfig(
            String path,
            Set<String> methods,
            String root,
            String indexFile,
            boolean directoryListing,
            String directoryDefaultFile,
            String uploadDirectory,
            RedirectConfig redirect,
            Map<String, String> cgiHandlers) {
        this.path = path;
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
}
