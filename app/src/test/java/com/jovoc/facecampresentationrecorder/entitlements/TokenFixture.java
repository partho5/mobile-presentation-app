package com.jovoc.facecampresentationrecorder.entitlements;

import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Test helper: an EC P-256 key pair that signs tokens the way the server does (ES256 JWS). */
final class TokenFixture {

    static final String KID = "k1";
    static final long NOW = 1_800_000_000L;
    static final long DAY = 24 * 60 * 60L;

    final KeyPair pair;

    TokenFixture() {
        this(newPair());
    }

    TokenFixture(KeyPair pair) {
        this.pair = pair;
    }

    static KeyPair newPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Public keys as the app's BuildConfig carries them: kid to base64 SPKI. */
    Map<String, String> keys() {
        return Collections.singletonMap(KID, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    Map<String, String> keysFor(String kid) {
        return Collections.singletonMap(kid, keys().get(KID));
    }

    /** A token valid at {@link #NOW}: issued then, good for 14 days. */
    String token(String plan, String sid, String... features) {
        return token(plan, sid, NOW, NOW + 14 * DAY, features);
    }

    String token(String plan, String sid, long iat, long exp, String... features) {
        return sign(header(KID), payload(1, plan, sid, iat, exp, features));
    }

    static JSONObject header(String kid) {
        return json("{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}");
    }

    static JSONObject payload(int v, String plan, String sid, long iat, long exp, String... features) {
        try {
            JSONObject p = new JSONObject();
            p.put("v", v);
            p.put("plan", plan);
            JSONArray list = new JSONArray();
            for (String f : features) list.put(f);
            p.put("features", list);
            if (sid != null) p.put("sid", sid);
            p.put("iat", iat);
            p.put("exp", exp);
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String sign(JSONObject header, JSONObject payload) {
        String input = b64(header.toString()) + "." + b64(payload.toString());
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signRaw(input));
    }

    /** A compact JWS with the given parts as they are (to build tampered tokens). */
    static String join(String header, String payload, String signature) {
        return header + "." + payload + "." + signature;
    }

    static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** JWS signature (r||s, 64 bytes) of the signing input. */
    byte[] signRaw(String signingInput) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(pair.getPrivate());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return derToJose(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** DER SEQUENCE of two INTEGERs to fixed-width r||s. */
    static byte[] derToJose(byte[] der) {
        int i = 2; // 0x30, one length byte (a P-256 signature is under 128 bytes)
        byte[] out = new byte[64];
        for (int part = 0; part < 2; part++) {
            int len = der[i + 1] & 0xff;
            int start = i + 2;
            while (len > 32) { // sign byte or padding zero
                start++;
                len--;
            }
            System.arraycopy(der, start, out, part * 32 + (32 - len), len);
            i += 2 + (der[i + 1] & 0xff);
        }
        return out;
    }

    static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** In-memory {@link KeyValueStore}. */
    static final class MemoryKv implements KeyValueStore {
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
}
