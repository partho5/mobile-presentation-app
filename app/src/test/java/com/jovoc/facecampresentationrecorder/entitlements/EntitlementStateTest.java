package com.jovoc.facecampresentationrecorder.entitlements;

import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.DAY;
import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class EntitlementStateTest {

    private static final String CATALOGUE = "{\"plans\":["
            + "{\"key\":\"free\",\"name\":\"Free\",\"monthlyPriceCents\":0,\"currency\":\"USD\"},"
            + "{\"key\":\"starter\",\"name\":\"Starter\",\"monthlyPriceCents\":500,\"currency\":\"USD\"},"
            + "{\"key\":\"pro\",\"name\":\"Pro Plus\",\"monthlyPriceCents\":1200,\"currency\":\"USD\"}],"
            + "\"features\":{\"crop_9_16\":\"free\",\"bg_replace\":\"starter\",\"new_thing\":\"pro\"}}";

    private final TokenFixture fx = new TokenFixture();
    private final TokenFixture.MemoryKv kv = new TokenFixture.MemoryKv();
    private final AtomicLong nowMs = new AtomicLong(NOW * 1000);
    private final AtomicInteger changes = new AtomicInteger();
    private String session = "s1";

    private EntitlementState state() {
        return state(fx.keys());
    }

    private EntitlementState state(Map<String, String> keys) {
        EntitlementState s = new EntitlementState(new EntitlementsPrefs(kv), keys, nowMs::get,
                () -> session, Runnable::run);
        s.addListener(changes::incrementAndGet);
        return s;
    }

    private String pro() {
        return fx.token("pro", "s1", "crop_9_16", "floating_cam", "crop_4_5", "crop_1_1", "bg_replace", "bg_custom");
    }

    @Test
    public void emptyStoreGivesTheFreeDefaults() {
        EntitlementState s = state();
        assertTrue(s.has(Feature.CROP_9_16));
        assertTrue(s.has(Feature.FLOATING_CAM));
        assertTrue(s.has(Feature.DRAWING_BOARD));
        assertTrue(s.has(Feature.SLIDE_INK));
        assertTrue(s.has(Feature.AUTO_PUBLISH));
        assertFalse(s.has(Feature.CROP_4_5));
        assertFalse(s.has(Feature.CROP_1_1));
        assertFalse(s.has(Feature.BG_REPLACE));
        assertFalse(s.has(Feature.BG_CUSTOM));
        assertFalse(s.has("unheard_of"));
        assertFalse(s.has(null));
        assertEquals("free", s.plan());
        assertEquals("Free", s.planName());
        assertEquals(0L, s.tokenExpiresAtSec());
        assertEquals(0L, s.lastFetchAtMs());
        assertEquals(0, changes.get());
    }

    @Test
    public void withoutPublicKeysEveryTokenIsInvalidAndTheUserIsFree() {
        EntitlementState s = state(Collections.<String, String>emptyMap());
        assertFalse(s.accept(pro(), CATALOGUE));
        assertFalse(s.has(Feature.BG_REPLACE));
        assertTrue(s.has(Feature.CROP_9_16));
        assertNull(kv.data.get("token"));
        // Even a token already sitting in the store counts for nothing.
        kv.data.put("token", pro());
        assertFalse(state(Collections.<String, String>emptyMap()).has(Feature.BG_REPLACE));
    }

    @Test
    public void anAcceptedTokenUnlocksExactlyItsFeatures() {
        EntitlementState s = state();
        String starter = fx.token("starter", "s1", "crop_9_16", "floating_cam", "crop_4_5", "crop_1_1");
        assertTrue(s.accept(starter, CATALOGUE));
        assertTrue(s.has(Feature.CROP_4_5));
        assertTrue(s.has(Feature.CROP_1_1));
        assertFalse(s.has(Feature.BG_REPLACE));
        // The token's list is the truth: a free default it leaves out is off.
        assertFalse(s.has(Feature.SLIDE_INK));
        assertEquals("starter", s.plan());
        assertEquals("Starter", s.planName());
        assertEquals(NOW + 14 * DAY, s.tokenExpiresAtSec());
        assertEquals(NOW, s.tokenIssuedAtSec());
        assertEquals(NOW * 1000, s.lastFetchAtMs());
        assertEquals(1, changes.get());
    }

    @Test
    public void theAcceptedTokenSurvivesARestart() {
        assertTrue(state().accept(pro(), CATALOGUE));
        EntitlementState afterRestart = new EntitlementState(new EntitlementsPrefs(kv), fx.keys(), nowMs::get,
                () -> "s1", Runnable::run);
        assertTrue(afterRestart.has(Feature.BG_REPLACE));
        assertEquals("Pro Plus", afterRestart.planName());
    }

    @Test
    public void aBadTokenChangesNothing() {
        EntitlementState s = state();
        assertTrue(s.accept(pro(), CATALOGUE));
        String stored = kv.data.get("token");
        String tampered = pro().replaceFirst("\\.[^.]*\\.", "." + TokenFixture.b64("{}") + ".");
        assertFalse(s.accept(tampered, null));
        assertFalse(s.accept("garbage", CATALOGUE));
        assertFalse(s.accept(null, CATALOGUE));
        assertEquals(stored, kv.data.get("token"));
        assertTrue(s.has(Feature.BG_REPLACE));
        assertEquals(1, changes.get());
    }

    @Test
    public void aTokenForAnotherSessionIsNotAccepted() {
        session = "s2";
        EntitlementState s = state();
        assertFalse(s.accept(pro(), CATALOGUE)); // issued for s1
        assertFalse(s.has(Feature.BG_REPLACE));
        session = null; // signed out: only an anonymous token fits
        assertFalse(s.accept(pro(), CATALOGUE));
        assertTrue(s.accept(fx.token("free", null, "crop_9_16"), null));
    }

    @Test
    public void aStoredTokenOfAnotherSessionIsIgnoredOnLoad() {
        assertTrue(state().accept(pro(), CATALOGUE));
        session = "s2"; // signed in as someone else since
        assertFalse(state().has(Feature.BG_REPLACE));
        session = null; // or signed out
        assertFalse(state().has(Feature.BG_REPLACE));
    }

    @Test
    public void aTamperedStoredTokenIsIgnoredOnLoad() {
        assertTrue(state().accept(pro(), CATALOGUE));
        String[] p = kv.data.get("token").split("\\.");
        kv.data.put("token", TokenFixture.join(p[0], TokenFixture.b64(TokenFixture.payload(1, "agency", "s1",
                NOW, NOW + DAY, "bg_replace").toString()), p[2]));
        EntitlementState s = state();
        assertFalse(s.has(Feature.BG_REPLACE));
        assertEquals("free", s.plan());
    }

    @Test
    public void aStoredTokenIsIgnoredOnceItsExpiryHasPassed() {
        assertTrue(state().accept(pro(), CATALOGUE));
        nowMs.set((NOW + 15 * DAY) * 1000);
        EntitlementState s = state();
        assertFalse(s.has(Feature.BG_REPLACE));
        assertEquals("free", s.plan());
    }

    @Test
    public void aTokenThatRunsOutWhileTheAppIsOpenStopsCountingAndTellsListeners() {
        EntitlementState s = state();
        assertTrue(s.accept(pro(), CATALOGUE));
        assertTrue(s.has(Feature.BG_REPLACE));
        assertEquals(1, changes.get());
        nowMs.set((NOW + 14 * DAY) * 1000);
        assertFalse(s.has(Feature.BG_REPLACE));
        assertEquals("free", s.plan());
        assertEquals(2, changes.get());
        s.has(Feature.BG_REPLACE);
        assertEquals(2, changes.get()); // told once
    }

    @Test
    public void aTurnedBackClockMakesTheStoredTokenInvalid() {
        assertTrue(state().accept(pro(), CATALOGUE)); // maxIat = NOW
        nowMs.set((NOW - DAY - 60) * 1000);
        assertFalse(state().has(Feature.BG_REPLACE));
        nowMs.set((NOW - DAY + 60) * 1000);
        assertTrue(state().has(Feature.BG_REPLACE));
    }

    @Test
    public void maxIatOnlyMovesForward() {
        EntitlementState s = state();
        assertTrue(s.accept(fx.token("pro", "s1", NOW, NOW + 14 * DAY, "bg_replace"), null));
        assertEquals(String.valueOf(NOW), kv.data.get("max_iat"));
        // An older (but still valid) token is accepted, yet the newest iat seen stays.
        assertTrue(s.accept(fx.token("pro", "s1", NOW - 3 * DAY, NOW + 10 * DAY, "bg_replace"), null));
        assertEquals(String.valueOf(NOW), kv.data.get("max_iat"));
        assertTrue(s.accept(fx.token("pro", "s1", NOW + 1000, NOW + 15 * DAY, "bg_replace"), null));
        assertEquals(String.valueOf(NOW + 1000), kv.data.get("max_iat"));
    }

    @Test
    public void clearGoesBackToFreeAndEmptiesTheStore() {
        EntitlementState s = state();
        assertTrue(s.accept(pro(), CATALOGUE));
        s.clear();
        assertFalse(s.has(Feature.BG_REPLACE));
        assertTrue(s.has(Feature.CROP_9_16));
        assertEquals("free", s.plan());
        assertTrue(kv.data.isEmpty());
        assertEquals(0L, s.lastFetchAtMs());
        assertTrue(s.catalogue().plans().isEmpty());
        assertEquals(2, changes.get());
        assertFalse(state().has(Feature.BG_REPLACE));
    }

    @Test
    public void clearBeforeAnythingWasReadStaysQuiet() {
        EntitlementState s = state();
        s.clear();
        assertEquals(0, changes.get());
        assertEquals("free", s.plan());
    }

    @Test
    public void listenersHearOnlyRealChanges() {
        EntitlementState s = state();
        assertTrue(s.accept(pro(), CATALOGUE));
        assertEquals(1, changes.get());
        assertTrue(s.accept(pro(), CATALOGUE)); // same plan, same features
        assertEquals(1, changes.get());
        assertTrue(s.accept(fx.token("starter", "s1", "crop_9_16", "crop_4_5"), CATALOGUE));
        assertEquals(2, changes.get());
    }

    @Test
    public void aRemovedListenerHearsNothing() {
        EntitlementState s = state();
        AtomicInteger removed = new AtomicInteger();
        Entitlements.Listener l = removed::incrementAndGet;
        s.addListener(l);
        s.removeListener(l);
        assertTrue(s.accept(pro(), CATALOGUE));
        assertEquals(0, removed.get());
        assertEquals(1, changes.get());
    }

    @Test
    public void catalogueGivesNamesAndLowestPlans() {
        EntitlementState s = state();
        assertEquals("pro", s.lowestPlanFor(Feature.BG_REPLACE)); // before any catalogue: the default
        assertNull(s.lowestPlanFor("new_thing"));
        assertTrue(s.accept(fx.token("pro", "s1", "bg_replace"), CATALOGUE));
        assertEquals("starter", s.lowestPlanFor(Feature.BG_REPLACE)); // the server moved it
        assertEquals("pro", s.lowestPlanFor("new_thing"));
        assertEquals("starter", s.lowestPlanFor(Feature.CROP_4_5)); // not listed: the default
        assertNull(s.lowestPlanFor("never_heard"));
        assertNull(s.lowestPlanFor(null));
        assertEquals("Pro Plus", s.planName());
        assertEquals(3, s.catalogue().plans().size());
        assertEquals(1200L, s.catalogue().plan("pro").monthlyPriceCents);
        assertEquals("USD", s.catalogue().plan("starter").currency);
    }

    @Test
    public void aMissingOrBrokenCatalogueKeepsTheStoredOne() {
        EntitlementState s = state();
        assertTrue(s.accept(fx.token("pro", "s1", "bg_replace"), CATALOGUE));
        assertTrue(s.accept(fx.token("pro", "s1", NOW + 5, NOW + 14 * DAY, "bg_replace"), null));
        assertTrue(s.accept(fx.token("pro", "s1", NOW + 6, NOW + 14 * DAY, "bg_replace"), "not json"));
        assertEquals("Pro Plus", s.planName());
        assertEquals(CATALOGUE, kv.data.get("catalogue"));
    }

    @Test
    public void planNameFallsBackToTheCapitalisedKey() {
        EntitlementState s = state();
        assertTrue(s.accept(fx.token("agency", "s1", "bg_replace"), "{\"plans\":[],\"features\":{}}"));
        assertEquals("Agency", s.planName());
        assertEquals("agency", s.plan());
    }

    @Test
    public void aSessionStoreThatThrowsMeansFreeNotACrash() {
        assertTrue(state().accept(pro(), CATALOGUE));
        EntitlementState s = new EntitlementState(new EntitlementsPrefs(kv), fx.keys(), nowMs::get,
                () -> {
                    throw new IllegalStateException("Secure storage unavailable");
                }, Runnable::run);
        assertFalse(s.has(Feature.BG_REPLACE));
        assertTrue(s.has(Feature.CROP_9_16));
        assertFalse(s.accept(pro(), CATALOGUE));
    }

    @Test
    public void garbageInTheStoreIsHarmless() {
        kv.data.put("token", "junk");
        kv.data.put("catalogue", "{{{");
        kv.data.put("max_iat", "x");
        kv.data.put("last_fetch_at", "y");
        EntitlementState s = state();
        assertEquals("free", s.plan());
        assertTrue(s.catalogue().plans().isEmpty());
        assertEquals(0L, s.lastFetchAtMs());
        assertTrue(s.accept(pro(), CATALOGUE));
    }

    /** A state whose brand is read from {@code brand[0]} each time, so a test can switch brands. */
    private EntitlementState withBrand(String[] brand) {
        EntitlementState s = new EntitlementState(new EntitlementsPrefs(kv), fx.keys(), nowMs::get,
                () -> session, () -> brand[0], Runnable::run);
        s.addListener(changes::incrementAndGet);
        return s;
    }

    @Test
    public void aTokenCountsOnlyForTheBrandItWasFetchedFor() {
        String[] brand = {"b1"};
        EntitlementState s = withBrand(brand);
        assertTrue(s.accept(pro(), CATALOGUE));
        assertTrue(s.has(Feature.BG_REPLACE));
        assertEquals("b1", kv.data.get("brand"));
        brand[0] = "b2";
        s.brandChanged();
        assertFalse(s.has(Feature.BG_REPLACE));
        assertEquals("free", s.plan());
        assertTrue(s.has(Feature.CROP_9_16));
        assertEquals(2, changes.get());
        // Back to b1 before b2 was fetched: b1's token still counts for b1.
        brand[0] = "b1";
        s.brandChanged();
        assertTrue(s.has(Feature.BG_REPLACE));
        assertEquals(3, changes.get());
    }

    @Test
    public void aStoredTokenOfAnotherBrandIsIgnoredOnLoad() {
        String[] brand = {"b1"};
        assertTrue(withBrand(brand).accept(pro(), CATALOGUE));
        brand[0] = "b2";
        EntitlementState afterRestart = withBrand(brand);
        assertFalse(afterRestart.has(Feature.BG_REPLACE));
        assertEquals("free", afterRestart.plan());
        brand[0] = null; // the server's default brand
        assertFalse(withBrand(brand).has(Feature.BG_REPLACE));
    }

    @Test
    public void aReplyFetchedForAnotherBrandIsDropped() {
        String[] brand = {"b1"};
        EntitlementState s = withBrand(brand);
        brand[0] = "b2"; // switched while the request for b1 was on its way
        assertFalse(s.accept(pro(), CATALOGUE, "b1"));
        assertFalse(s.has(Feature.BG_REPLACE));
        assertTrue(s.accept(fx.token("pro", "s1", "bg_replace"), CATALOGUE, "b2"));
        assertTrue(s.has(Feature.BG_REPLACE));
        assertEquals("b2", kv.data.get("brand"));
    }

    @Test
    public void aTokenSavedBeforeBrandsWereRecordedIsNotTrusted() {
        assertTrue(state().accept(pro(), CATALOGUE));
        kv.data.remove("brand"); // as a build from before brands were recorded leaves it
        EntitlementState s = state();
        assertFalse(s.has(Feature.BG_REPLACE));
        assertEquals(0L, s.lastFetchAtMs()); // so the next foreground check fetches again
    }
}
