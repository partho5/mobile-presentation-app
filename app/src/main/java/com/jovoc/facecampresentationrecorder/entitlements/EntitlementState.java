package com.jovoc.facecampresentationrecorder.entitlements;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The logic behind {@link Entitlements}, with its collaborators passed in so it runs without
 * Android. Reads are in memory and never throw; the stored token is loaded and verified on first
 * use, and a token that runs out while the app is open stops counting at once.
 */
final class EntitlementState {

    /** The verified token (null: none, Free defaults apply) and the catalogue; replaced whole. */
    private static final class Snapshot {
        final EntitlementToken token;
        final Catalogue catalogue;

        Snapshot(EntitlementToken token, Catalogue catalogue) {
            this.token = token;
            this.catalogue = catalogue;
        }

        String plan() {
            return token != null ? token.plan : Feature.PLAN_FREE;
        }

        Set<String> features() {
            return token != null ? token.features : null;
        }

        /** Same plan and same feature set, i.e. nothing the user could notice changed. */
        boolean sameAccess(Snapshot o) {
            return plan().equals(o.plan()) && Objects.equals(features(), o.features());
        }
    }

    private final EntitlementsPrefs prefs;
    private final Map<String, String> keys;
    private final LongSupplier clockMs;
    private final Supplier<String> sessionId;
    private final Supplier<String> brandId;
    private final Executor notifier;
    private final CopyOnWriteArrayList<Entitlements.Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private volatile Snapshot snap;

    /** Same as the full constructor with no brand: every token then belongs to the server's default. */
    EntitlementState(EntitlementsPrefs prefs, Map<String, String> keys, LongSupplier clockMs,
                     Supplier<String> sessionId, Executor notifier) {
        this(prefs, keys, clockMs, sessionId, () -> null, notifier);
    }

    /**
     * @param keys kid to base64 SPKI; empty makes every token invalid
     * @param sessionId the signed-in session's id, or null when signed out
     * @param brandId the brand the phone works in now, or null for the server's default. A token
     *                counts only for the brand it was fetched for, so one brand's plan never decides
     *                what another brand may do.
     * @param notifier where listeners are called (the main thread in the app)
     */
    EntitlementState(EntitlementsPrefs prefs, Map<String, String> keys, LongSupplier clockMs,
                     Supplier<String> sessionId, Supplier<String> brandId, Executor notifier) {
        this.prefs = prefs;
        this.keys = keys;
        this.clockMs = clockMs;
        this.sessionId = sessionId;
        this.brandId = brandId;
        this.notifier = notifier;
    }

    /** The brand as the prefs store it: "" for the server's default, so a missing record stays distinguishable. */
    private static String brandKey(String brand) {
        return brand == null ? "" : brand;
    }

    boolean has(String feature) {
        if (feature == null) return false;
        EntitlementToken t = current().token;
        return t != null ? t.features.contains(feature) : Feature.isFreeByDefault(feature);
    }

    String plan() {
        return current().plan();
    }

    String planName() {
        Snapshot s = current();
        Catalogue.Plan p = s.catalogue.plan(s.plan());
        return p != null ? p.name : Feature.defaultName(s.plan());
    }

    /** The server's lowest plan for the feature, else the app's default, else null (unknown feature). */
    String lowestPlanFor(String feature) {
        if (feature == null) return null;
        String fromServer = current().catalogue.lowestPlanFor(feature);
        return fromServer != null ? fromServer : Feature.DEFAULT_LOWEST.get(feature);
    }

    Catalogue catalogue() {
        return current().catalogue;
    }

    long lastFetchAtMs() {
        return prefs.lastFetchAt();
    }

    /** Issued-at of the token in force (epoch seconds), 0 when there is none. */
    long tokenIssuedAtSec() {
        EntitlementToken t = current().token;
        return t != null ? t.iat : 0L;
    }

    long tokenExpiresAtSec() {
        EntitlementToken t = current().token;
        return t != null ? t.exp : 0L;
    }

    /** {@link #accept(String, String, String)} for the brand the phone works in right now. */
    boolean accept(String jws, String catalogueJson) {
        return accept(jws, catalogueJson, brandId.get());
    }

    /**
     * Keeps a freshly fetched token if, and only if, it verifies for the current session and
     * clock, and was fetched for the brand the phone works in now (a reply that arrives after a
     * brand change is dropped). A null catalogue (or one that is not a JSON object) leaves the
     * stored one.
     * @param fetchedFor the brand the request was sent for, or null for the server's default
     * @return true when the token was accepted
     */
    boolean accept(String jws, String catalogueJson, String fetchedFor) {
        Snapshot before;
        Snapshot next;
        synchronized (lock) {
            before = loaded();
            if (!brandKey(fetchedFor).equals(brandKey(brandId.get()))) return false;
            long nowMs = clockMs.getAsLong();
            EntitlementToken t;
            try {
                t = EntitlementToken.verify(jws, keys, nowMs / 1000, prefs.maxIat(), sessionId.get());
            } catch (RuntimeException e) {
                return false;
            }
            if (t == null) return false;
            Catalogue fresh = Catalogue.parse(catalogueJson);
            prefs.saveFetched(jws, fresh != null ? catalogueJson : null, nowMs, Math.max(prefs.maxIat(), t.iat),
                    brandKey(fetchedFor));
            next = new Snapshot(t, fresh != null ? fresh : before.catalogue);
            snap = next;
        }
        if (!next.sameAccess(before)) notifyChanged();
        return true;
    }

    /**
     * The phone switched brand: the stored token counts only if it was fetched for the new one, so
     * the old brand's plan stops at once. Listeners hear about it when the plan the user sees changes.
     */
    void brandChanged() {
        Snapshot before;
        Snapshot next;
        synchronized (lock) {
            before = snap;
            next = read();
            snap = next;
        }
        if (before != null && !before.sameAccess(next)) notifyChanged();
    }

    /** Forgets everything stored (sign-out, another session): back to the Free defaults. */
    void clear() {
        Snapshot before;
        Snapshot next = new Snapshot(null, Catalogue.EMPTY);
        synchronized (lock) {
            before = snap;
            prefs.clear();
            snap = next;
        }
        // Before the first read nobody has seen anything else than Free.
        if (before != null && !before.sameAccess(next)) notifyChanged();
    }

    void addListener(Entitlements.Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    void removeListener(Entitlements.Listener l) {
        listeners.remove(l);
    }

    private Snapshot current() {
        Snapshot s = snap;
        if (s == null) {
            synchronized (lock) {
                s = loaded();
            }
        }
        EntitlementToken t = s.token;
        if (t != null && clockMs.getAsLong() / 1000 >= t.exp) s = expire(s);
        return s;
    }

    /** The snapshot, reading it from the store the first time. Call with the lock held. */
    private Snapshot loaded() {
        Snapshot s = snap;
        if (s == null) {
            s = read();
            snap = s;
        }
        return s;
    }

    private Snapshot read() {
        Catalogue catalogue = Catalogue.parse(prefs.catalogueJson());
        if (catalogue == null) catalogue = Catalogue.EMPTY;
        EntitlementToken token = null;
        try {
            String jws = prefs.token();
            // Only a token fetched for the brand in use now counts. A token saved before brands were
            // recorded has no brand (null) and is not trusted.
            if (jws != null && Objects.equals(prefs.brand(), brandKey(brandId.get()))) {
                token = EntitlementToken.verify(jws, keys, clockMs.getAsLong() / 1000, prefs.maxIat(),
                        sessionId.get());
            }
        } catch (RuntimeException e) {
            // Whatever went wrong (e.g. the session store cannot be opened), the safe answer is Free.
        }
        return new Snapshot(token, catalogue);
    }

    /** The token in {@code stale} ran out: drop it (the stored copy stays until a fetch replaces it). */
    private Snapshot expire(Snapshot stale) {
        Snapshot next;
        synchronized (lock) {
            if (snap != stale) return snap;
            next = new Snapshot(null, stale.catalogue);
            snap = next;
        }
        notifyChanged();
        return next;
    }

    private void notifyChanged() {
        for (Entitlements.Listener l : listeners) notifier.execute(l::onEntitlementsChanged);
    }
}
