package com.jovoc.facecampresentationrecorder.entitlements;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

/**
 * Which paid features this phone may use, answered offline from the plan token the server signed.
 * {@code Entitlements.has(Feature.BG_REPLACE)} is static, in memory, never throws and never touches
 * the network or blocks the UI. With no valid token (never fetched, expired, signed out, another
 * account's) only the features that are free by default are on; before {@link #init} the same.
 * Fetching a token is a separate job; it hands the result to {@link #accept}.
 */
public final class Entitlements {

    /** Called on the main thread after the plan or the set of usable features changed. */
    public interface Listener {
        void onEntitlementsChanged();
    }

    private static volatile EntitlementState state;

    private Entitlements() {
    }

    /** Call once at startup. Cheap: the stored token is read and verified on first use. */
    public static synchronized void init(Context context) {
        if (state != null) return;
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        state = new EntitlementState(
                EntitlementsPrefs.open(app),
                EntitlementToken.parseKeys(BuildConfig.ENTITLEMENT_PUBLIC_KEYS),
                System::currentTimeMillis,
                () -> SessionManager.get(app).repository().store().sessionId(),
                () -> EntitlementsApi.brandInUse(SessionManager.get(app).repository().store()),
                main::post);
    }

    public static boolean has(String feature) {
        EntitlementState s = state;
        return s != null ? s.has(feature) : Feature.isFreeByDefault(feature);
    }

    /** The plan key in force ("free" without a valid token). */
    public static String plan() {
        EntitlementState s = state;
        return s != null ? s.plan() : Feature.PLAN_FREE;
    }

    /** The plan's display name ("Pro"): from the catalogue when there is one. */
    public static String planName() {
        EntitlementState s = state;
        return s != null ? s.planName() : Feature.defaultName(Feature.PLAN_FREE);
    }

    /** Key of the lowest plan that includes the feature; null for a feature nobody knows. */
    public static String lowestPlanFor(String feature) {
        EntitlementState s = state;
        return s != null ? s.lowestPlanFor(feature) : Feature.DEFAULT_LOWEST.get(feature);
    }

    /** Plans and prices as the server last sent them (empty before the first fetch). */
    public static Catalogue catalogue() {
        EntitlementState s = state;
        return s != null ? s.catalogue() : Catalogue.EMPTY;
    }

    /**
     * Keeps a token fetched from the server if it verifies (right key, not expired, clock sane,
     * this session) and was fetched for the brand the phone works in now; otherwise changes
     * nothing. {@code catalogueJson} is the response's catalogue object, or null to keep the stored
     * one. {@code workspaceId} is the brand the request was sent for, null for the server's default.
     * Blocks briefly; call off the main thread.
     * @return true when the token was accepted
     */
    public static boolean accept(String token, String catalogueJson, String workspaceId) {
        EntitlementState s = state;
        return s != null && s.accept(token, catalogueJson, workspaceId);
    }

    /**
     * The phone switched brand: the plan of the previous brand stops counting at once, and the
     * plan of the new one counts only once it has been fetched for it.
     */
    public static void brandChanged() {
        EntitlementState s = state;
        if (s != null) s.brandChanged();
    }

    /** Back to the Free defaults and an empty store: sign-out, or a different session. */
    public static void clear() {
        EntitlementState s = state;
        if (s != null) s.clear();
    }

    /** Wall-clock millis of the last token accepted, 0 if none. */
    public static long lastFetchAtMs() {
        EntitlementState s = state;
        return s != null ? s.lastFetchAtMs() : 0L;
    }

    /** Issued-at of the token in force (epoch seconds); 0 when there is none. */
    public static long tokenIssuedAtSec() {
        EntitlementState s = state;
        return s != null ? s.tokenIssuedAtSec() : 0L;
    }

    /** Expiry of the token in force (epoch seconds); 0 when there is none. */
    public static long tokenExpiresAtSec() {
        EntitlementState s = state;
        return s != null ? s.tokenExpiresAtSec() : 0L;
    }

    /** Listeners are held strongly: remove them when the screen goes away. */
    public static void addListener(Listener l) {
        EntitlementState s = state;
        if (s != null) s.addListener(l);
    }

    public static void removeListener(Listener l) {
        EntitlementState s = state;
        if (s != null) s.removeListener(l);
    }
}
