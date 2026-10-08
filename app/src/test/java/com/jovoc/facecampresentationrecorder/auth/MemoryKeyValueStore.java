package com.jovoc.facecampresentationrecorder.auth;

import java.util.HashMap;
import java.util.Map;

/** In-memory {@link KeyValueStore} for tests. */
class MemoryKeyValueStore implements KeyValueStore {
    final Map<String, String> data = new HashMap<>();

    @Override
    public synchronized String get(String key) {
        return data.get(key);
    }

    @Override
    public synchronized void putAll(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null) data.remove(e.getKey());
            else data.put(e.getKey(), e.getValue());
        }
    }

    @Override
    public synchronized void clear() {
        data.clear();
    }
}
