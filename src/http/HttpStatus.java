package http;

public final class HttpStatus {
    private HttpStatus() {
    }

    public static String reasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Content Too Large";
            case 500 -> "Internal Server Error";
            case 504 -> "Gateway Timeout";
            default -> "HTTP Status " + statusCode;
        };
    }
}
