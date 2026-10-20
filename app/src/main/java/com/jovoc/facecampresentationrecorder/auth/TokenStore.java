package com.jovoc.facecampresentationrecorder.auth;

import java.util.HashMap;
import java.util.Map;

/** The device's session: token pair, session id, email and device name. */
public class TokenStore {

    /** Access tokens are renewed this long before they expire. */
    public static final long REFRESH_MARGIN_MS = 30_000L;

    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_SESSION = "session_id";
    private static final String K_EXPIRES = "access_expires_at";
    private static final String K_EMAIL = "email";
    private static final String K_DEVICE = "device_name";
    private static final String K_WORKSPACE = "workspace_id";
    private static final String K_WORKSPACE_NAME = "workspace_name";

    private final KeyValueStore kv;
    private final Object lock = new Object();
    private volatile Runnable sessionListener;

    public TokenStore(KeyValueStore kv) {
        this.kv = kv;
    }

    /**
     * Called (on the calling thread) when the session ends ({@link #clear}) or a different session
     * id is saved, i.e. whenever things tied to the old session must go. A refresh that keeps the
     * session id does not count. A failing listener never breaks the store.
     */
    public void setSessionListener(Runnable listener) {
        this.sessionListener = listener;
    }

    /** Stores a freshly issued or rotated pair. Email/device name are kept unless given. */
    public void saveTokens(String access, String refresh, String sessionId, long expiresInSeconds,
                           long nowMs) {
        Map<String, String> m = new HashMap<>();
        m.put(K_ACCESS, access);
        m.put(K_REFRESH, refresh);
        m.put(K_SESSION, sessionId);
        m.put(K_EXPIRES, String.valueOf(nowMs + expiresInSeconds * 1000L));
        boolean newSession;
        synchronized (lock) {
            String previous = kv.get(K_SESSION);
            kv.putAll(m);
            newSession = sessionId != null && !sessionId.isEmpty() && !sessionId.equals(previous);
        }
        if (newSession) notifySessionChanged();
    }

    public void saveIdentity(String email, String deviceName) {
        Map<String, String> m = new HashMap<>();
        m.put(K_EMAIL, email);
        m.put(K_DEVICE, deviceName);
        synchronized (lock) {
            kv.putAll(m);
        }
    }

    /** A session exists when a refresh token is held; the access token may be stale. */
    public boolean hasSession() {
        String r = refreshToken();
        return r != null && !r.isEmpty();
    }

    public String accessToken() {
        return read(K_ACCESS);
    }

    public String refreshToken() {
        return read(K_REFRESH);
    }

    public String sessionId() {
        return read(K_SESSION);
    }

    public String email() {
        return read(K_EMAIL);
    }

    public String deviceName() {
        return read(K_DEVICE);
    }

    /** The workspace picked on this device, or null for the server's default. */
    public String workspaceId() {
        String v = read(K_WORKSPACE);
        return v == null || v.isEmpty() ? null : v;
    }

    public String workspaceName() {
        return read(K_WORKSPACE_NAME);
    }

    public void saveWorkspace(String id, String name) {
        Map<String, String> m = new HashMap<>();
        m.put(K_WORKSPACE, id);
        m.put(K_WORKSPACE_NAME, name == null ? "" : name);
        synchronized (lock) {
            kv.putAll(m);
        }
    }

    /** Back to the server's default workspace (a null value removes the key). */
    public void clearWorkspace() {
        Map<String, String> m = new HashMap<>();
        m.put(K_WORKSPACE, null);
        m.put(K_WORKSPACE_NAME, null);
        synchronized (lock) {
            kv.putAll(m);
        }
    }

    public long accessExpiresAtMs() {
        String v = read(K_EXPIRES);
        if (v == null) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** True when the access token is missing or within the margin of its expiry. */
    public boolean accessNeedsRefresh(long nowMs) {
        String a = accessToken();
        return a == null || a.isEmpty() || nowMs >= accessExpiresAtMs() - REFRESH_MARGIN_MS;
    }

    public void clear() {
        synchronized (lock) {
            kv.clear();
        }
        notifySessionChanged();
    }

    private void notifySessionChanged() {
        Runnable l = sessionListener;
        if (l == null) return;
        try {
            l.run();
        } catch (RuntimeException ignored) {
            // Whatever hangs off the session must not stop it from starting or ending.
        }
    }

    private String read(String key) {
        synchronized (lock) {
            return kv.get(key);
        }
    }
}
