# LocalServer

LocalServer is a lightweight HTTP/1.1 web server written in Java with core `java.nio` and `java.net` APIs. It uses a single-threaded, event-driven `Selector` loop to accept connections, read requests, and write responses without relying on server frameworks such as Netty, Jetty, or Grizzly.

The goal of the project is to show how a web server works from the server side: opening listening sockets, accepting browser connections, parsing raw HTTP requests, routing them to static files or CGI scripts, and building valid HTTP responses by hand.

The project keeps only the required features for the assignment:

- one process and one thread
- one `Selector`-based event loop for accepts, reads, and writes
- configurable virtual servers, hostnames, and ports
- configurable routes, redirects, directory indexes, directory listing, and method limits
- `GET`, `POST`, and `DELETE`
- file uploads and safe deletes
- cookies and server-side sessions
- custom/default error pages for `400`, `403`, `404`, `405`, `413`, and `500`
- `Content-Length` and chunked request bodies
- one CGI handler type: Python scripts through `ProcessBuilder`

Bonus features such as a second CGI runtime, admin dashboard, and metrics endpoint are intentionally not included.

## Project Structure

```text
.
|-- config.json              # Server and route configuration
|-- cgi-bin/test.py          # Example Python CGI script
|-- error_pages/             # Custom error pages
|-- src/
|   |-- Main.java            # Entry point
|   |-- config/              # JSON config parsing and validation
|   |-- handlers/            # Static files, uploads, deletes, CGI, errors
|   |-- http/                # HTTP request/response parsing and building
|   |-- routing/             # Route matching and method checks
|   |-- server/              # Selector loop and client connection state
|   `-- utils/               # Cookie/session management
|-- tests/LocalServerTests.java
|-- uploads/                 # Upload destination
`-- www/                     # Static website root
```

## How It Works

`Main` loads `config.json`, validates it, then starts `Server`.

`Server` opens one `Selector` and registers every configured listening socket on it. The event loop uses that same selector for:

- `OP_ACCEPT`: accept a new client socket
- `OP_READ`: read available request bytes from one client
- `OP_WRITE`: write available response bytes to one client

Each client is represented by `ClientConnection`, which stores its socket, read buffer, accumulated request bytes, write buffer, listener host/port, remote address, and last activity time. Long requests are closed when they pass the configured timeout.

The server reads request bytes until it has complete headers and, when needed, a complete body. It supports both `Content-Length` and `Transfer-Encoding: chunked`. After parsing, `Router` chooses the best matching configured route, checks whether the method is allowed, then sends the request to `StaticFileHandler` or `CgiHandler`.

Responses are created by `HttpResponse`, which writes the HTTP status line, headers, `Content-Length`, `Connection: close`, and body bytes. Error responses come from custom configured files when they exist, otherwise a small default HTML page is generated.

## Configuration

The server is configured through `config.json`. The default file demonstrates the required settings:

- `host`: bind address, for example `127.0.0.1`
- `ports`: one or more ports for the server to listen on
- `server_names`: hostnames matched against the HTTP `Host` header
- `client_max_body_size`: maximum accepted request body size in bytes
- `client_timeout_ms`: idle timeout for incomplete or slow requests
- `upload_dir`: default upload directory
- `error_pages`: custom paths for `400`, `403`, `404`, `405`, `413`, and `500`
- `routes`: route table used by the router

Each route can define:

- `path`: URL prefix matched by the router
- `methods`: accepted HTTP methods
- `root`: filesystem root for the route
- `index`: default file for directory requests
- `redirect`: optional `301` or `302` redirection
- `cgi`: extension-to-command mapping, currently `.py` to `python3`
- `upload_dir`: upload destination for POST requests
- `directory_listing`: whether directory listing is allowed

The config loader supports multiple virtual servers through a top-level `servers` array. Multiple server blocks can share the same host and port if they use different `server_names`; the first matching listener is used as the default server when no hostname matches.

## HTTP Features

`GET` serves static files or runs CGI scripts when the route and extension match.

`POST` saves request bodies as uploaded files on upload routes. A filename can be supplied with the `X-Filename` header. Multipart form uploads are also handled.

`DELETE` removes regular files only when the route allows `DELETE` and the target stays inside the configured route root.

Cookies are parsed from the `Cookie` header. `SessionManager` creates a server-side session and sends `Set-Cookie: LOCALSERVER_SESSION=...` when the client has no valid session cookie.

CGI execution is intentionally limited to one type: Python scripts. `CgiHandler` runs the configured interpreter with the script path as the first argument, writes the request body to the process, and passes CGI-style environment variables such as `REQUEST_METHOD`, `QUERY_STRING`, `PATH_INFO`, `CONTENT_LENGTH`, and `HTTP_COOKIE`.

## Build And Run

```bash
javac -d out $(find src -name '*.java')
java -cp out Main config.json
```

The default configuration listens on:

- `http://127.0.0.1:8080`
- `http://127.0.0.1:8081`

Open either URL in a browser to test static file serving.

## Test Commands

Run the local test harness:

```bash
javac -cp out -d out tests/LocalServerTests.java
java -cp out LocalServerTests
```

Useful manual checks:

```bash
curl -i http://127.0.0.1:8080/
curl -i http://127.0.0.1:8080/not-found
curl -i http://127.0.0.1:8080/old
curl -i -X POST -H "X-Filename: hello.txt" --data-binary "hello" http://127.0.0.1:8080/uploads
curl -i http://127.0.0.1:8080/uploads/hello.txt
curl -i -X DELETE http://127.0.0.1:8080/uploads/hello.txt
curl -i -X POST -H "Content-Type: text/plain" --data "hello" http://127.0.0.1:8080/cgi/test.py/info?x=1
curl --http1.1 -i -X POST -H "Transfer-Encoding: chunked" --data-binary "chunked-body" http://127.0.0.1:8080/cgi/test.py/chunked
```

Virtual-host check:

```bash
curl --resolve test.com:8080:127.0.0.1 http://test.com:8080/
```

Stress test example:

```bash
siege -b http://127.0.0.1:8080/
```

Only run stress tests against local servers or systems you have explicit permission to test.

## Evaluator Notes

The implementation uses Java NIO `Selector` as the select-equivalent I/O multiplexing function. All socket accept, read, and write operations happen from the single event loop in `src/server/Server.java`.

Per selected client event, the server performs one non-blocking socket read or one non-blocking socket write, checks the return value, and closes the client when the socket reaches end-of-stream or an error occurs.

The project is intentionally small and does not include bonus features. The minimized scope makes it easier to explain and inspect the required HTTP server behavior.
