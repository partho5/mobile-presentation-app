package com.jovoc.facecampresentationrecorder.auth;

/** Minimal string store so {@link TokenStore} logic is testable without Android. */
public interface KeyValueStore {
    String get(String key);

    void putAll(java.util.Map<String, String> values);

    void clear();
}
