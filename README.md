# LocalServer

LocalServer is a lightweight HTTP/1.1 web server written in Java with core `java.nio` and `java.net` APIs. It uses a single-threaded, event-driven `Selector` loop to accept connections, read requests, and write responses without relying on server frameworks such as Netty, Jetty, or Grizzly.

The project serves static files, supports configurable routes, handles `GET`, `POST`, and `DELETE`, receives uploads, manages cookies and server-side sessions, serves custom error pages, decodes `Content-Length` and chunked request bodies, and can execute CGI scripts by file extension. The sample configuration includes multiple ports, redirects, directory listing, Python/Perl CGI examples, request logging, and a `/metrics` endpoint.

Build and run:

```bash
javac -d out $(find src -name '*.java')
java -cp out Main config.json
```

Run the local test harness:

```bash
javac -cp out -d out tests/LocalServerTests.java
java -cp out LocalServerTests
```
