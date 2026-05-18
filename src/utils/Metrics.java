package utils;

import java.util.HashMap;
import java.util.Map;

public class Metrics {
    private long acceptedConnections;
    private long completedRequests;
    private long activeConnections;
    private final Map<Integer, Long> statusCounts = new HashMap<>();

    public void connectionAccepted() {
        acceptedConnections++;
        activeConnections++;
    }

    public void connectionClosed() {
        if (activeConnections > 0) {
            activeConnections--;
        }
    }

    public void responseCompleted(int statusCode) {
        completedRequests++;
        statusCounts.merge(statusCode, 1L, Long::sum);
    }

    public String toJson(int sessionCount) {
        StringBuilder statuses = new StringBuilder();
        boolean first = true;

        for (Map.Entry<Integer, Long> entry : statusCounts.entrySet()) {
            if (!first) {
                statuses.append(",");
            }

            statuses.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }

        return """
                {
                  "accepted_connections": %d,
                  "active_connections": %d,
                  "completed_requests": %d,
                  "sessions": %d,
                  "status_counts": {%s}
                }
                """.formatted(acceptedConnections, activeConnections, completedRequests, sessionCount, statuses);
    }
}
