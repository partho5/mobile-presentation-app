package com.jovoc.facecampresentationrecorder.auth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.Map;

/**
 * {@link KeyValueStore} on EncryptedSharedPreferences (Keystore-backed master key).
 * If the encrypted file cannot be opened (keys lost after restore / reset), it is deleted
 * and recreated empty, which simply means the user is signed out.
 */
public class SecureKeyValueStore implements KeyValueStore {

    static final String FILE = "auth_secure";

    private final SharedPreferences prefs;

    public SecureKeyValueStore(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p;
        try {
            p = open(app);
        } catch (Exception first) {
            app.deleteSharedPreferences(FILE);
            try {
                p = open(app);
            } catch (Exception second) {
                throw new IllegalStateException("Secure storage unavailable", second);
            }
        }
        this.prefs = p;
    }

    private static SharedPreferences open(Context app) throws Exception {
        MasterKey key = new MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(app, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    @Override
    public String get(String key) {
        try {
            return prefs.getString(key, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void putAll(Map<String, String> values) {
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null) ed.remove(e.getKey());
            else ed.putString(e.getKey(), e.getValue());
        }
        ed.commit();
    }

    @Override
    public void clear() {
        prefs.edit().clear().commit();
    }
}
