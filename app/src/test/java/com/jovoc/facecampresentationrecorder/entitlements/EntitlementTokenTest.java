package com.jovoc.facecampresentationrecorder.entitlements;

import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.DAY;
import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.KID;
import static com.jovoc.facecampresentationrecorder.entitlements.TokenFixture.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

public class EntitlementTokenTest {

    private final TokenFixture fx = new TokenFixture();
    private final Map<String, String> keys = fx.keys();

    private EntitlementToken verify(String jws, long now, long maxIat, String session) {
        return EntitlementToken.verify(jws, keys, now, maxIat, session);
    }

    @Test
    public void validTokenGivesItsFields() {
        String jws = fx.token("pro", "s1", "crop_9_16", "bg_replace");
        EntitlementToken t = verify(jws, NOW + 60, 0, "s1");
        assertNotNull(t);
        assertEquals(KID, t.kid);
        assertEquals("pro", t.plan);
        assertEquals(new HashSet<>(Arrays.asList("crop_9_16", "bg_replace")), t.features);
        assertEquals("s1", t.sid);
        assertEquals(NOW, t.iat);
        assertEquals(NOW + 14 * DAY, t.exp);
    }

    @Test
    public void signaturesOfEveryShapeVerify() {
        // r and s of varying length (leading zeros, high bit set) all convert to valid DER.
        for (int i = 0; i < 60; i++) {
            String jws = fx.token("pro", "s", "f" + i);
            assertNotNull("signature " + i, verify(jws, NOW, 0, "s"));
        }
    }

    @Test
    public void joseToDerStripsZerosAndAddsSignBytes() {
        byte[] jose = new byte[64];
        jose[2] = (byte) 0x80; // r: two leading zeros, then a high bit -> 00 80 .. (31 bytes)
        for (int i = 3; i < 32; i++) jose[i] = 1;
        for (int i = 32; i < 64; i++) jose[i] = 0x7f; // s: 32 bytes, no sign byte
        byte[] der = EntitlementToken.joseToDer(jose);
        assertEquals(2 + 2 + 31 + 2 + 32, der.length);
        assertEquals(0x30, der[0]);
        assertEquals(4 + 31 + 32, der[1]);
        assertEquals(0x02, der[2]);
        assertEquals(31, der[3]);
        assertEquals(0x00, der[4]);
        assertEquals((byte) 0x80, der[5]);
        assertEquals(0x02, der[4 + 31]);
        assertEquals(32, der[5 + 31]);
        assertEquals(0x7f, der[6 + 31]);
    }

    @Test
    public void tamperedPayloadIsRejected() {
        String[] p = fx.token("starter", "s1", "crop_4_5").split("\\.");
        String upgraded = TokenFixture.b64(TokenFixture.payload(1, "agency", "s1", NOW, NOW + 14 * DAY,
                "bg_replace", "bg_custom").toString());
        assertNull(verify(TokenFixture.join(p[0], upgraded, p[2]), NOW, 0, "s1"));
    }

    @Test
    public void tamperedSignatureIsRejected() {
        String[] p = fx.token("pro", "s1", "bg_replace").split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(p[2]);
        sig[10] ^= 0x01;
        String bad = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        assertNull(verify(TokenFixture.join(p[0], p[1], bad), NOW, 0, "s1"));
        assertNull(verify(TokenFixture.join(p[0], p[1], ""), NOW, 0, "s1"));
        assertNull(verify(TokenFixture.join(p[0], p[1], "AAAA"), NOW, 0, "s1"));
    }

    @Test
    public void tokenSignedByAnotherKeyIsRejected() {
        TokenFixture attacker = new TokenFixture();
        String jws = attacker.token("agency", "s1", "bg_replace");
        assertNull(verify(jws, NOW, 0, "s1"));
    }

    @Test
    public void unknownKidIsRejected() {
        String jws = fx.sign(TokenFixture.header("rotated"),
                TokenFixture.payload(1, "pro", "s1", NOW, NOW + DAY, "bg_replace"));
        assertNull(verify(jws, NOW, 0, "s1"));
        // The same token verifies once the key is known under that kid.
        assertNotNull(EntitlementToken.verify(jws, fx.keysFor("rotated"), NOW, 0, "s1"));
    }

    @Test
    public void otherAlgorithmsAreRejected() {
        for (String alg : new String[]{"none", "HS256", "ES384", "RS256"}) {
            JSONObject header = TokenFixture.json("{\"alg\":\"" + alg + "\",\"kid\":\"" + KID + "\"}");
            String jws = fx.sign(header, TokenFixture.payload(1, "pro", "s1", NOW, NOW + DAY, "bg_replace"));
            assertNull(alg, verify(jws, NOW, 0, "s1"));
        }
    }

    @Test
    public void emptyKeyMapRejectsEverything() {
        String jws = fx.token("pro", "s1", "bg_replace");
        assertNull(EntitlementToken.verify(jws, Collections.<String, String>emptyMap(), NOW, 0, "s1"));
        assertNull(EntitlementToken.verify(jws, EntitlementToken.parseKeys(""), NOW, 0, "s1"));
        assertNull(EntitlementToken.verify(jws, null, NOW, 0, "s1"));
    }

    @Test
    public void garbageNeverThrows() {
        String[] junk = {null, "", ".", "..", "a.b", "a.b.c.d", "not a token", "e30.e30.e30",
                "eyJhbGciOiJFUzI1NiJ9.e30.AAAA", "\u0000.\u0000.\u0000", new String(new char[20000]).replace('\0', 'x')};
        for (String s : junk) assertNull(s == null ? "null" : s.substring(0, Math.min(10, s.length())),
                verify(s, NOW, 0, "s1"));
    }

    @Test
    public void unknownKeyMaterialIsRejectedNotThrown() {
        String jws = fx.token("pro", "s1", "bg_replace");
        assertNull(EntitlementToken.verify(jws, Collections.singletonMap(KID, "not base64 !!"), NOW, 0, "s1"));
        assertNull(EntitlementToken.verify(jws, Collections.singletonMap(KID, "AAAA"), NOW, 0, "s1"));
    }

    @Test
    public void wrongVersionOrShapeIsRejected() {
        long exp = NOW + DAY;
        assertNull(verify(fx.sign(TokenFixture.header(KID), TokenFixture.payload(2, "pro", "s", NOW, exp)), NOW, 0, "s"));
        assertNull(verify(fx.sign(TokenFixture.header(KID), TokenFixture.payload(1, "", "s", NOW, exp)), NOW, 0, "s"));
        // exp not after iat
        assertNull(verify(fx.sign(TokenFixture.header(KID), TokenFixture.payload(1, "pro", "s", NOW, NOW)), NOW, 0, "s"));
        // features must be strings
        JSONObject p = TokenFixture.payload(1, "pro", "s", NOW, exp);
        p.remove("features");
        assertNull(verify(fx.sign(TokenFixture.header(KID), p), NOW, 0, "s"));
        try {
            p.put("features", new org.json.JSONArray("[1,2]"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertNull(verify(fx.sign(TokenFixture.header(KID), p), NOW, 0, "s"));
    }

    @Test
    public void expiredTokenIsRejected() {
        String jws = fx.token("pro", "s1", "bg_replace");
        long exp = NOW + 14 * DAY;
        assertNotNull(verify(jws, exp - 1, 0, "s1"));
        assertNull(verify(jws, exp, 0, "s1"));
        assertNull(verify(jws, exp + DAY, 0, "s1"));
    }

    @Test
    public void clockTurnedBackIsRejected() {
        String jws = fx.token("pro", "s1", "bg_replace"); // iat = NOW
        long seen = NOW + 10 * DAY; // a newer token was accepted before
        assertNotNull(verify(jws, seen, seen, "s1"));
        assertNotNull(verify(jws, seen - DAY + 1, seen, "s1")); // within the 24 h slack
        assertNull(verify(jws, seen - DAY - 1, seen, "s1")); // clock set back more than a day
        assertNull(verify(jws, NOW + DAY, seen + 5 * DAY, "s1"));
    }

    @Test
    public void tokenFromTheFutureIsRejected() {
        // Its own iat counts as seen: a clock more than a day before it was issued is wrong.
        String jws = fx.token("pro", "s1", NOW, NOW + 14 * DAY, "bg_replace");
        assertNotNull(verify(jws, NOW - DAY + 1, 0, "s1"));
        assertNull(verify(jws, NOW - DAY - 1, 0, "s1"));
    }

    @Test
    public void tokenOfAnotherSessionIsRejected() {
        String jws = fx.token("pro", "s1", "bg_replace");
        assertNull(verify(jws, NOW, 0, "s2"));
        assertNull(verify(jws, NOW, 0, null)); // signed out now
        assertNull(verify(jws, NOW, 0, ""));
    }

    @Test
    public void sidlessTokenOnlyCountsWhileSignedOut() {
        String anonymous = fx.token("free", null, "crop_9_16");
        assertNull(verify(anonymous, NOW, 0, "s1")); // signed in: an anonymous token is not theirs
        assertNotNull(verify(anonymous, NOW, 0, null));
        assertNotNull(verify(anonymous, NOW, 0, ""));
    }

    @Test
    public void parseKeysReadsKidEqualsBase64Entries() {
        Map<String, String> k = EntitlementToken.parseKeys("a=QUJD, b=REVG==;c=R0hJ\n");
        assertEquals(3, k.size());
        assertEquals("QUJD", k.get("a"));
        assertEquals("REVG==", k.get("b"));
        assertEquals("R0hJ", k.get("c"));
        assertTrue(EntitlementToken.parseKeys("").isEmpty());
        assertTrue(EntitlementToken.parseKeys(null).isEmpty());
        assertTrue(EntitlementToken.parseKeys("=abc,kid=,noequals").isEmpty());
    }

    @Test
    public void isUsableMatchesVerify() {
        EntitlementToken t = EntitlementToken.parse(fx.token("pro", "s1", "bg_replace"), keys);
        assertNotNull(t);
        assertTrue(t.isUsable(NOW, 0, "s1"));
        assertFalse(t.isUsable(t.exp, 0, "s1"));
    }
}
