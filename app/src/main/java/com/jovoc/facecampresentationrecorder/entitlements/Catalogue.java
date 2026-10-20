package com.jovoc.facecampresentationrecorder.entitlements;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The server's price list, from the {@code catalogue} of GET /entitlements: the plans (key, name,
 * monthly price, in the order the server sends them) and the lowest plan of each feature. Display
 * data only; what a phone may use comes from the signed token.
 */
public final class Catalogue {

    public static final class Plan {
        public final String key;
        public final String name;
        public final long monthlyPriceCents;
        public final String currency;

        Plan(String key, String name, long monthlyPriceCents, String currency) {
            this.key = key;
            this.name = name;
            this.monthlyPriceCents = monthlyPriceCents;
            this.currency = currency;
        }
    }

    public static final Catalogue EMPTY = new Catalogue(Collections.<Plan>emptyList(),
            Collections.<String, String>emptyMap());

    private final List<Plan> plans;
    private final Map<String, String> features;

    private Catalogue(List<Plan> plans, Map<String, String> features) {
        this.plans = plans;
        this.features = features;
    }

    /** The catalogue in {@code json} (a JSON object; unusable members are skipped), or null if it is not one. */
    static Catalogue parse(String json) {
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            List<Plan> plans = new ArrayList<>();
            JSONArray list = root.optJSONArray("plans");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                String key = p == null ? null : text(p, "key");
                if (key == null || key.isEmpty()) continue;
                String name = text(p, "name");
                String currency = text(p, "currency");
                plans.add(new Plan(key, name == null || name.isEmpty() ? Feature.defaultName(key) : name,
                        p.optLong("monthlyPriceCents", 0L), currency == null || currency.isEmpty() ? "USD" : currency));
            }
            Map<String, String> features = new HashMap<>();
            JSONObject f = root.optJSONObject("features");
            if (f != null) {
                for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                    String feature = it.next();
                    String lowest = text(f, feature);
                    if (lowest != null && !lowest.isEmpty()) features.put(feature, lowest);
                }
            }
            return new Catalogue(Collections.unmodifiableList(plans), Collections.unmodifiableMap(features));
        } catch (Exception e) {
            return null;
        }
    }

    /** The plans, cheapest first as the server orders them. */
    public List<Plan> plans() {
        return plans;
    }

    /** The plan with this key (case does not matter), or null. */
    public Plan plan(String key) {
        if (key == null) return null;
        for (Plan p : plans) {
            if (p.key.equalsIgnoreCase(key)) return p;
        }
        return null;
    }

    /** The lowest plan the server lists for a feature, or null when it does not list it. */
    public String lowestPlanFor(String feature) {
        return feature == null ? null : features.get(feature);
    }

    private static String text(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }
}
