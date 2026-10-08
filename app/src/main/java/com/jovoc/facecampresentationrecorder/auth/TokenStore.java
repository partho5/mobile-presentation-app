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

    private final KeyValueStore kv;
    private final Object lock = new Object();

    public TokenStore(KeyValueStore kv) {
        this.kv = kv;
    }

    /** Stores a freshly issued or rotated pair. Email/device name are kept unless given. */
    public void saveTokens(String access, String refresh, String sessionId, long expiresInSeconds,
                           long nowMs) {
        Map<String, String> m = new HashMap<>();
        m.put(K_ACCESS, access);
        m.put(K_REFRESH, refresh);
        m.put(K_SESSION, sessionId);
        m.put(K_EXPIRES, String.valueOf(nowMs + expiresInSeconds * 1000L));
        synchronized (lock) {
            kv.putAll(m);
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
    }

    private String read(String key) {
        synchronized (lock) {
            return kv.get(key);
        }
    }
}
