package com.jovoc.facecampresentationrecorder.auth;

import java.util.HashMap;
import java.util.Map;

/** The device's session: token pair, session id, email, device name and the picked brand. */
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
    private static final String K_WORKSPACE_LOST = "workspace_access_lost";

    private final KeyValueStore kv;
    private final Object lock = new Object();
    private volatile Runnable sessionListener;
    private volatile Runnable brandListener;

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

    /**
     * Called (on the calling thread) when the brand in use may have changed: a brand is picked or
     * saved, or its access is marked lost. Not called for a refresh of the same brand.
     */
    public void setBrandListener(Runnable listener) {
        this.brandListener = listener;
    }

    /**
     * Stores a freshly issued or rotated pair. Email/device name are kept unless given. A different
     * session id also forgets the brand picked for the old session (the sign-in pins a new one).
     */
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
            newSession = sessionId != null && !sessionId.isEmpty() && !sessionId.equals(previous);
            if (newSession) {
                m.put(K_WORKSPACE, null);
                m.put(K_WORKSPACE_NAME, null);
                m.put(K_WORKSPACE_LOST, null);
            }
            kv.putAll(m);
        }
        if (newSession) {
            notifySessionChanged();
            notifyBrandChanged();
        }
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

    /** The brand picked on this device, or null when none is stored yet. */
    public String workspaceId() {
        String v = read(K_WORKSPACE);
        return v == null || v.isEmpty() ? null : v;
    }

    public String workspaceName() {
        return read(K_WORKSPACE_NAME);
    }

    /**
     * True when the server refused the picked brand (403 WORKSPACE_FORBIDDEN): this phone has no
     * access to it any more. The pick itself stays; the person chooses a brand instead.
     */
    public boolean workspaceAccessLost() {
        return "1".equals(read(K_WORKSPACE_LOST)) && workspaceId() != null;
    }

    /** Stores the brand picked on this device. A new pick clears any "access lost" mark. */
    public void saveWorkspace(String id, String name) {
        Map<String, String> m = new HashMap<>();
        m.put(K_WORKSPACE, id);
        m.put(K_WORKSPACE_NAME, name == null ? "" : name);
        m.put(K_WORKSPACE_LOST, null);
        synchronized (lock) {
            kv.putAll(m);
        }
        notifyBrandChanged();
    }

    /**
     * Marks the picked brand as no longer accessible. Never clears the pick: nothing moves to another
     * brand by itself. No-op when no brand is picked.
     */
    public void markWorkspaceAccessLost() {
        if (workspaceId() == null) return;
        synchronized (lock) {
            kv.putAll(Map.of(K_WORKSPACE_LOST, "1"));
        }
        notifyBrandChanged();
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
        notifyBrandChanged();
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

    private void notifyBrandChanged() {
        Runnable l = brandListener;
        if (l == null) return;
        try {
            l.run();
        } catch (RuntimeException ignored) {
            // The plan follows the brand; a failing listener must not stop the store.
        }
    }

    private String read(String key) {
        synchronized (lock) {
            return kv.get(key);
        }
    }
}
