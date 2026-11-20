package com.jovoc.facecampresentationrecorder.entitlements;

import android.content.Context;
import android.content.SharedPreferences;

import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;

import java.util.HashMap;
import java.util.Map;

/**
 * What the phone remembers about the plan: the signed token, the catalogue JSON, when the last
 * fetch succeeded and the newest iat accepted. A plain SharedPreferences file on purpose: the token
 * is signed and holds nothing secret, and the Keystore-backed secure store is wiped at sign-out.
 * The file is excluded from backup and device transfer (data_extraction_rules.xml): a restored
 * copy would carry another phone's session.
 */
final class EntitlementsPrefs {

    static final String FILE = "entitlements_prefs";
    private static final String K_TOKEN = "token";
    private static final String K_CATALOGUE = "catalogue";
    private static final String K_LAST_FETCH_AT = "last_fetch_at";
    private static final String K_MAX_IAT = "max_iat";
    private static final String K_BRAND = "brand";

    private final KeyValueStore kv;

    EntitlementsPrefs(KeyValueStore kv) {
        this.kv = kv;
    }

    static EntitlementsPrefs open(Context context) {
        return new EntitlementsPrefs(new SharedPrefsStore(
                context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)));
    }

    String token() {
        return kv.get(K_TOKEN);
    }

    String catalogueJson() {
        return kv.get(K_CATALOGUE);
    }

    /** The brand the stored token was fetched for ("" for the server's default), or null when none was recorded. */
    String brand() {
        return kv.get(K_BRAND);
    }

    /**
     * Wall-clock millis of the last verified 200, or 0. A token saved before brands were recorded is
     * not trusted (see {@link EntitlementState}), so it counts as never fetched and the next
     * foreground check fetches it again.
     */
    long lastFetchAt() {
        return brand() == null ? 0L : readLong(K_LAST_FETCH_AT);
    }

    /** Newest iat (epoch seconds) of any token accepted so far, or 0. */
    long maxIat() {
        return readLong(K_MAX_IAT);
    }

    /**
     * Stores a verified token and the brand it was fetched for ("" for the server's default). A null
     * catalogue keeps the one already stored.
     */
    void saveFetched(String token, String catalogueJson, long fetchedAtMs, long maxIatSeconds, String brand) {
        Map<String, String> m = new HashMap<>();
        m.put(K_TOKEN, token);
        if (catalogueJson != null) m.put(K_CATALOGUE, catalogueJson);
        m.put(K_LAST_FETCH_AT, String.valueOf(fetchedAtMs));
        m.put(K_MAX_IAT, String.valueOf(maxIatSeconds));
        m.put(K_BRAND, brand);
        kv.putAll(m);
    }

    void clear() {
        kv.clear();
    }

    private long readLong(String key) {
        String v = kv.get(key);
        if (v == null) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static final class SharedPrefsStore implements KeyValueStore {

        private final SharedPreferences sp;

        SharedPrefsStore(SharedPreferences sp) {
            this.sp = sp;
        }

        @Override
        public String get(String key) {
            try {
                return sp.getString(key, null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public void putAll(Map<String, String> values) {
            SharedPreferences.Editor ed = sp.edit();
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getValue() == null) ed.remove(e.getKey());
                else ed.putString(e.getKey(), e.getValue());
            }
            ed.apply();
        }

        @Override
        public void clear() {
            sp.edit().clear().apply();
        }
    }
}
