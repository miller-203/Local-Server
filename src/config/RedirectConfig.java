package config;

public class RedirectConfig {
    private final int statusCode;
    private final String location;

    public RedirectConfig(int statusCode, String location) {
        this.statusCode = statusCode;
        this.location = location;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getLocation() {
        return location;
    }
}
