package com.jovoc.facecampresentationrecorder.entitlements;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Which brand a plan token must belong to: a lost brand's plan never counts for another brand. */
public class BrandInUseTest {

    private final TokenStore store = new TokenStore(new MapStore());

    @Test
    public void noSessionMeansNoBrand() {
        assertNull(EntitlementsApi.brandInUse(store));
    }

    @Test
    public void theStoredPickIsTheBrandInUse() {
        store.saveTokens("acc", "ref", "sid", 900, 0);
        store.saveWorkspace("w1", "Acme");
        assertEquals("w1", EntitlementsApi.brandInUse(store));
    }

    @Test
    public void aLostBrandIsNoBrandAnyTokenCanBeFor() {
        store.saveTokens("acc", "ref", "sid", 900, 0);
        store.saveWorkspace("w1", "Acme");
        store.markWorkspaceAccessLost();
        assertEquals(EntitlementsApi.LOST_BRAND, EntitlementsApi.brandInUse(store));
        assertNotEquals("w1", EntitlementsApi.brandInUse(store));
    }

    /** In-memory store for these tests. */
    private static final class MapStore implements KeyValueStore {
        private final Map<String, String> data = new HashMap<>();

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
}
