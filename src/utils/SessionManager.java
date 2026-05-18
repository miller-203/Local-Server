package utils;

import http.HttpRequest;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

public class SessionManager {
    public static final String COOKIE_NAME = "LOCALSERVER_SESSION";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new HashMap<>();

    public SessionContext resolve(HttpRequest request) {
        String sessionId = request.getCookie(COOKIE_NAME);
        boolean fresh = false;

        if (sessionId == null || !sessions.containsKey(sessionId)) {
            sessionId = newSessionId();
            sessions.put(sessionId, new Session(sessionId));
            fresh = true;
        }

        Session session = sessions.get(sessionId);
        session.touch();
        return new SessionContext(session, fresh);
    }

    public int size() {
        return sessions.size();
    }

    private String newSessionId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static class SessionContext {
        private final Session session;
        private final boolean fresh;

        public SessionContext(Session session, boolean fresh) {
            this.session = session;
            this.fresh = fresh;
        }

        public Session getSession() {
            return session;
        }

        public boolean isFresh() {
            return fresh;
        }

        public String setCookieHeader() {
            return COOKIE_NAME + "=" + session.getId() + "; Path=/; HttpOnly; SameSite=Lax";
        }
    }

    public static class Session {
        private final String id;
        private final Map<String, String> data = new HashMap<>();
        private Instant lastAccessedAt;

        private Session(String id) {
            this.id = id;
            this.lastAccessedAt = Instant.now();
        }

        public String getId() {
            return id;
        }

        public Map<String, String> getData() {
            return data;
        }

        public Instant getLastAccessedAt() {
            return lastAccessedAt;
        }

        private void touch() {
            lastAccessedAt = Instant.now();
        }
    }
}
