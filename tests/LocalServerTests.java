import config.ConfigLoader;
import config.RedirectConfig;
import config.RouteConfig;
import config.VirtualServerConfig;
import http.HttpParser;
import http.HttpRequest;
import http.HttpResponse;
import routing.Router;
import utils.Metrics;
import utils.SessionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class LocalServerTests {
    public static void main(String[] args) throws Exception {
        testParserCookies();
        testSingleAndMultiplePortConfig();
        testDuplicatePortConfig();
        testVirtualHostConfigValidation();
        testInvalidServerBlockDoesNotKillValidServers();
        testRoutesMethodsUploadsDeleteRedirectAndCgi();
        System.out.println("All LocalServer tests passed");
    }

    private static void testParserCookies() {
        HttpParser parser = new HttpParser();
        HttpRequest request = parser.parse("""
                POST /submit?x=1 HTTP/1.1\r
                Host: localhost\r
                Cookie: a=1; b=two\r
                Content-Length: 5\r
                \r
                hello""");

        assertEquals("POST", request.getMethod(), "method");
        assertEquals("/submit", request.getPath(), "path");
        assertEquals("x=1", request.getQueryString(), "query");
        assertEquals("1", request.getCookie("a"), "cookie a");
        assertEquals("two", request.getCookie("b"), "cookie b");
        assertEquals("hello", request.getBody(), "body");
    }

    private static void testSingleAndMultiplePortConfig() throws Exception {
        Path single = Files.createTempFile("localserver-single-", ".json");
        Files.writeString(single, """
                {
                  "host": "127.0.0.1",
                  "ports": [8080],
                  "client_max_body_size": 128,
                  "error_pages": {"404": "./error_pages/404.html"},
                  "routes": [
                    {"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}
                  ]
                }
                """);

        assertEquals(1, new ConfigLoader().load(single.toString()).getPorts().size(), "single port config");
        assertEquals(128L, new ConfigLoader().load(single.toString()).getServers().get(0).getClientMaxBodySize(),
                "body limit config");
        assertEquals("./error_pages/404.html",
                new ConfigLoader().load(single.toString()).getServers().get(0).getErrorPages().get(404),
                "error page config");
        Files.deleteIfExists(single);

        Path multiple = Files.createTempFile("localserver-multi-", ".json");
        Files.writeString(multiple, """
                {
                  "host": "127.0.0.1",
                  "ports": [8080, 8081],
                  "routes": [
                    {"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}
                  ]
                }
                """);

        assertEquals(2, new ConfigLoader().load(multiple.toString()).getPorts().size(), "multiple port config");
        Files.deleteIfExists(multiple);
    }

    private static void testDuplicatePortConfig() throws Exception {
        Path config = Files.createTempFile("localserver-config-", ".json");
        Files.writeString(config, """
                {
                  "host": "127.0.0.1",
                  "ports": [8080, 8080],
                  "routes": [
                    {"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}
                  ]
                }
                """);

        assertThrows(() -> new ConfigLoader().load(config.toString()), "duplicate port config");
        Files.deleteIfExists(config);
    }

    private static void testVirtualHostConfigValidation() throws Exception {
        Path sharedPortConfig = Files.createTempFile("localserver-vhosts-", ".json");
        Files.writeString(sharedPortConfig, """
                {
                  "servers": [
                    {
                      "host": "127.0.0.1",
                      "ports": [8080],
                      "server_names": ["one.local"],
                      "routes": [{"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}]
                    },
                    {
                      "host": "127.0.0.1",
                      "ports": [8080],
                      "server_names": ["two.local"],
                      "routes": [{"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}]
                    }
                  ]
                }
                """);

        assertEquals(2, new ConfigLoader().load(sharedPortConfig.toString()).getServers().size(),
                "shared port virtual hosts");
        Files.deleteIfExists(sharedPortConfig);

        Path duplicateNameConfig = Files.createTempFile("localserver-vhosts-duplicate-", ".json");
        Files.writeString(duplicateNameConfig, """
                {
                  "servers": [
                    {
                      "host": "127.0.0.1",
                      "ports": [8080],
                      "server_names": ["same.local"],
                      "routes": [{"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}]
                    },
                    {
                      "host": "127.0.0.1",
                      "ports": [8080],
                      "server_names": ["same.local"],
                      "routes": [{"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}]
                    }
                  ]
                }
                """);

        assertThrows(() -> new ConfigLoader().load(duplicateNameConfig.toString()),
                "duplicate shared-port virtual host");
        Files.deleteIfExists(duplicateNameConfig);
    }

    private static void testInvalidServerBlockDoesNotKillValidServers() throws Exception {
        Path config = Files.createTempFile("localserver-partial-", ".json");
        Files.writeString(config, """
                {
                  "servers": [
                    {
                      "host": "127.0.0.1",
                      "ports": [8080],
                      "server_names": ["valid.local"],
                      "routes": [{"path": "/", "methods": ["GET"], "root": ".", "index": "index.html"}]
                    },
                    {
                      "host": "127.0.0.1",
                      "ports": [8081],
                      "server_names": ["bad.local"],
                      "routes": [{"path": "/", "methods": ["PATCH"], "root": ".", "index": "index.html"}]
                    }
                  ]
                }
                """);

        assertEquals(1, new ConfigLoader().load(config.toString()).getServers().size(),
                "invalid server block ignored");
        Files.deleteIfExists(config);
    }

    private static void testRoutesMethodsUploadsDeleteRedirectAndCgi() throws Exception {
        Path root = Files.createTempDirectory("localserver-www-");
        Path uploads = Files.createTempDirectory("localserver-uploads-");
        Path cgiRoot = Files.createTempDirectory("localserver-cgi-");

        Files.writeString(root.resolve("index.html"), "<h1>Hello</h1>");
        Files.writeString(cgiRoot.resolve("test.sh"), """
                printf 'Content-Type: text/plain\\r\\n\\r\\n'
                printf "$REQUEST_METHOD:$QUERY_STRING:"
                cat
                """);
        Files.writeString(cgiRoot.resolve("status.sh"), """
                printf 'Content-Type: text/plain\\r\\n'
                printf 'Status: 201 Created\\r\\n\\r\\n'
                printf 'created'
                """);

        VirtualServerConfig server = new VirtualServerConfig(
                "127.0.0.1",
                java.util.List.of(8080),
                Set.of("localhost", "127.0.0.1"),
                java.util.List.of(
                        new RouteConfig(
                                "/old",
                                Set.of("GET"),
                                ".",
                                "index.html",
                                false,
                                null,
                                null,
                                new RedirectConfig(301, "/"),
                                Map.of()),
                        new RouteConfig(
                                "/cgi",
                                Set.of("GET", "POST"),
                                cgiRoot.toString(),
                                "index.html",
                                false,
                                null,
                                null,
                                null,
                                Map.of(".sh", "sh")),
                        new RouteConfig(
                                "/uploads",
                                Set.of("GET", "POST", "DELETE"),
                                uploads.toString(),
                                "index.html",
                                true,
                                null,
                                uploads.toString(),
                                null,
                                Map.of()),
                        new RouteConfig(
                                "/",
                                Set.of("GET"),
                                root.toString(),
                                "index.html")),
                Map.of(),
                1024 * 1024,
                uploads.toString());

        SessionManager sessions = new SessionManager();
        Router router = new Router(new Metrics(), sessions);

        HttpResponse get = router.route(request("GET", "/", null, null), server, sessions.resolve(request("GET", "/", null, null)));
        assertEquals(200, get.getStatusCode(), "GET /");

        HttpResponse method = router.route(request("POST", "/", null, "body"), server, sessions.resolve(request("POST", "/", null, "body")));
        assertEquals(405, method.getStatusCode(), "POST / method guard");

        HttpRequest uploadRequest = request("POST", "/uploads", null, "stored");
        uploadRequest.addHeader("X-Filename", "hello.txt");
        HttpResponse upload = router.route(uploadRequest, server, sessions.resolve(uploadRequest));
        assertEquals(201, upload.getStatusCode(), "upload status");
        assertEquals("stored", Files.readString(uploads.resolve("hello.txt")), "uploaded file");

        HttpResponse uploadedGet = router.route(request("GET", "/uploads/hello.txt", null, null), server,
                sessions.resolve(request("GET", "/uploads/hello.txt", null, null)));
        assertEquals(200, uploadedGet.getStatusCode(), "uploaded GET");
        assertEquals("stored", new String(uploadedGet.getBody(), StandardCharsets.UTF_8), "uploaded bytes");

        HttpResponse delete = router.route(request("DELETE", "/uploads/hello.txt", null, null), server,
                sessions.resolve(request("DELETE", "/uploads/hello.txt", null, null)));
        assertEquals(204, delete.getStatusCode(), "delete status");

        HttpResponse redirect = router.route(request("GET", "/old", null, null), server,
                sessions.resolve(request("GET", "/old", null, null)));
        assertEquals(301, redirect.getStatusCode(), "redirect status");
        assertEquals("/", redirect.getHeaders().get("Location"), "redirect location");

        HttpResponse cgi = router.route(request("POST", "/cgi/test.sh/info", "a=1", "chunk"), server,
                sessions.resolve(request("POST", "/cgi/test.sh/info", "a=1", "chunk")));
        assertEquals(200, cgi.getStatusCode(), "cgi status");
        assertEquals("POST:a=1:chunk", new String(cgi.getBody(), StandardCharsets.UTF_8), "cgi output");

        HttpResponse cgiStatus = router.route(request("GET", "/cgi/status.sh", null, null), server,
                sessions.resolve(request("GET", "/cgi/status.sh", null, null)));
        assertEquals(201, cgiStatus.getStatusCode(), "cgi Status header");

        HttpResponse cgiExtensionGuard = router.route(request("GET", "/cgi/test.shx", null, null), server,
                sessions.resolve(request("GET", "/cgi/test.shx", null, null)));
        assertEquals(404, cgiExtensionGuard.getStatusCode(), "cgi extension boundary");
    }

    private static HttpRequest request(String method, String path, String query, String body) {
        HttpRequest request = new HttpRequest();
        request.setMethod(method);
        request.setPath(path);
        request.setQueryString(query == null ? "" : query);
        request.setVersion("HTTP/1.1");
        request.addHeader("Host", "localhost");

        if (body != null) {
            request.setBody(body);
            request.addHeader("Content-Length", String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));
        }

        return request;
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertThrows(ThrowingRunnable runnable, String label) throws Exception {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        }

        throw new AssertionError(label + " did not throw");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
