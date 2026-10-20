package com.jovoc.facecampresentationrecorder.entitlements;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The plan token the server signs (ES256 JWS: header {alg, typ, kid}, payload {v, plan, features,
 * sid?, iat, exp}). Nothing in it is secret; the signature is what makes it believable offline.
 * {@link #verify} is the only way the app accepts one, and it returns null for anything wrong
 * (bad shape, unknown kid, bad signature, expired, clock turned back, other session). Never throws.
 */
public final class EntitlementToken {

    static final int VERSION = 1;
    /** A clock more than this far behind the newest iat seen is treated as turned back. */
    static final long CLOCK_SLACK_SECONDS = 24 * 60 * 60L;

    private static final int MAX_LENGTH = 16 * 1024;
    private static final int JOSE_SIGNATURE_BYTES = 64;
    private static final int P256_FIELD_BITS = 256;

    public final String kid;
    public final String plan;
    public final Set<String> features;
    /** The session this token was issued for, or null for an anonymous (signed-out) token. */
    public final String sid;
    /** Issued-at and expiry, epoch seconds. */
    public final long iat;
    public final long exp;

    private EntitlementToken(String kid, String plan, Set<String> features, String sid, long iat, long exp) {
        this.kid = kid;
        this.plan = plan;
        this.features = features;
        this.sid = sid;
        this.iat = iat;
        this.exp = exp;
    }

    /**
     * The token when it is genuine and usable now, else null.
     * @param publicKeys kid to base64 SPKI (see {@link #parseKeys}); empty means nothing is genuine
     * @param maxIatSeenSeconds the newest iat accepted before (0 if none)
     * @param sessionId the signed-in session's id, or null/empty when signed out
     */
    public static EntitlementToken verify(String jws, Map<String, String> publicKeys, long nowSeconds,
                                          long maxIatSeenSeconds, String sessionId) {
        EntitlementToken t = parse(jws, publicKeys);
        return t != null && t.isUsable(nowSeconds, maxIatSeenSeconds, sessionId) ? t : null;
    }

    /** Signature and shape only: the token if the signature verifies, regardless of time or session. */
    static EntitlementToken parse(String jws, Map<String, String> publicKeys) {
        if (jws == null || jws.length() > MAX_LENGTH || publicKeys == null || publicKeys.isEmpty()) {
            return null;
        }
        try {
            String[] parts = jws.split("\\.", -1);
            if (parts.length != 3) return null;
            JSONObject header = new JSONObject(decodeText(parts[0]));
            if (!"ES256".equals(str(header, "alg"))) return null;
            String kid = str(header, "kid");
            String spki = kid == null ? null : publicKeys.get(kid);
            if (spki == null) return null;
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            if (!signatureValid(parts[0] + "." + parts[1], signature, decodeKey(spki))) return null;
            return fromPayload(kid, new JSONObject(decodeText(parts[1])));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Not expired; the clock not behind the newest iat seen (this token's own included) by more
     * than a day; and issued for this very session: the same sid, or no sid while signed out.
     */
    public boolean isUsable(long nowSeconds, long maxIatSeenSeconds, String sessionId) {
        if (nowSeconds >= exp) return false;
        if (nowSeconds < Math.max(maxIatSeenSeconds, iat) - CLOCK_SLACK_SECONDS) return false;
        String current = sessionId == null || sessionId.isEmpty() ? null : sessionId;
        return Objects.equals(sid, current);
    }

    /**
     * The BuildConfig key list: {@code kid=base64spki} entries separated by commas, semicolons or
     * whitespace. Base64 never contains those separators, and the first '=' ends the kid.
     */
    static Map<String, String> parseKeys(String config) {
        Map<String, String> keys = new HashMap<>();
        if (config != null) {
            for (String entry : config.split("[,;\\s]+")) {
                int eq = entry.indexOf('=');
                if (eq <= 0 || eq == entry.length() - 1) continue;
                keys.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        }
        return Collections.unmodifiableMap(keys);
    }

    private static EntitlementToken fromPayload(String kid, JSONObject p) throws JSONException {
        if (number(p, "v") != VERSION) return null;
        String plan = str(p, "plan");
        if (plan == null || plan.isEmpty()) return null;
        JSONArray list = p.getJSONArray("features");
        Set<String> features = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            Object f = list.get(i);
            if (!(f instanceof String)) return null;
            features.add((String) f);
        }
        String sid = str(p, "sid");
        if (sid != null && sid.isEmpty()) sid = null;
        long iat = number(p, "iat");
        long exp = number(p, "exp");
        if (iat <= 0 || exp <= iat) return null;
        return new EntitlementToken(kid, plan, Collections.unmodifiableSet(features), sid, iat, exp);
    }

    private static String decodeText(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    /** A string member, null when absent or null; any other type makes the whole token invalid. */
    private static String str(JSONObject o, String key) throws JSONException {
        if (!o.has(key) || o.isNull(key)) return null;
        Object v = o.get(key);
        if (!(v instanceof String)) throw new JSONException("not a string: " + key);
        return (String) v;
    }

    private static long number(JSONObject o, String key) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof Number)) throw new JSONException("not a number: " + key);
        return ((Number) v).longValue();
    }

    private static PublicKey decodeKey(String spki) throws GeneralSecurityException {
        String b64 = spki.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        if (!(key instanceof ECPublicKey)
                || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != P256_FIELD_BITS) {
            throw new InvalidKeyException("not a P-256 key");
        }
        return key;
    }

    private static boolean signatureValid(String signingInput, byte[] joseSignature, PublicKey key)
            throws GeneralSecurityException {
        if (joseSignature.length != JOSE_SIGNATURE_BYTES) return false;
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return s.verify(joseToDer(joseSignature));
    }

    /** JWS r||s (two 32-byte integers) to the DER SEQUENCE of two INTEGERs the JCA expects. */
    static byte[] joseToDer(byte[] jose) {
        int half = jose.length / 2;
        byte[] r = derInteger(jose, 0, half);
        byte[] s = derInteger(jose, half, half);
        // Each INTEGER is at most 33 bytes, so every length here fits in one byte.
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    /** Unsigned big-endian bytes as a minimal DER INTEGER value (no leading zeros, sign byte if needed). */
    private static byte[] derInteger(byte[] b, int off, int len) {
        int start = off;
        while (start < off + len - 1 && b[start] == 0) start++;
        boolean sign = (b[start] & 0x80) != 0;
        byte[] out = new byte[off + len - start + (sign ? 1 : 0)];
        System.arraycopy(b, start, out, sign ? 1 : 0, off + len - start);
        return out;
    }
}
