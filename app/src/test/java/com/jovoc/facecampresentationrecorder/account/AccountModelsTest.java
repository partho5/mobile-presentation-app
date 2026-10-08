package com.jovoc.facecampresentationrecorder.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AccountModelsTest {

    @Test
    public void workspaces_parseNameAndRole_skipDisabledAndIdless() throws Exception {
        String json = "[{\"id\":\"w1\",\"name\":\"Acme\",\"users\":[{\"role\":\"ADMIN\",\"disabled\":false}]},"
                + "{\"id\":\"w2\",\"name\":\"Off\",\"users\":[{\"role\":\"USER\",\"disabled\":true}]},"
                + "{\"name\":\"no id\"},"
                + "{\"id\":\"w3\",\"name\":\"\"}]";
        List<Workspace> list = Workspace.parseList(json);
        assertEquals(2, list.size());
        assertEquals("Acme", list.get(0).name);
        assertEquals("ADMIN", list.get(0).role);
        assertEquals("w3", list.get(1).name); // falls back to the id
        assertEquals("", list.get(1).role);
    }

    @Test
    public void balanceView_readsAvailableReservedLow() throws Exception {
        CreditBalance b = CreditBalance.parseBalanceView(
                "{\"available\":120,\"reserved\":30,\"plan\":\"pro\",\"allotment\":500,\"low\":true,\"canTopUp\":false}");
        assertEquals(120, b.available);
        assertEquals(30, b.reserved);
        assertTrue(b.hasReserved());
        assertTrue(b.low);
    }

    @Test(expected = org.json.JSONException.class)
    public void balanceView_withoutAvailable_isRejected() throws Exception {
        CreditBalance.parseBalanceView("{\"reserved\":1}");
    }

    @Test
    public void copilotCredits_isAvailableOnly() throws Exception {
        CreditBalance b = CreditBalance.parseCopilotCredits("{\"credits\":42}");
        assertEquals(42, b.available);
        assertFalse(b.hasReserved());
        assertFalse(b.low);
    }

    @Test
    public void devices_parseCurrentAndDates() throws Exception {
        String json = "[{\"id\":\"s1\",\"name\":\"Pixel 8\",\"userAgent\":null,\"ip\":\"10.0.0.2\","
                + "\"createdAt\":\"2026-10-01T10:00:00.000Z\",\"lastSeenAt\":\"2026-10-07T12:30:00.000Z\",\"current\":true},"
                + "{\"id\":\"s2\",\"name\":null,\"userAgent\":\"Mozilla\",\"ip\":null,"
                + "\"createdAt\":\"bad\",\"lastSeenAt\":\"2026-10-06T00:00:00Z\"},"
                + "{\"name\":\"no id\"}]";
        List<DeviceSession> list = DeviceSession.parseList(json);
        assertEquals(2, list.size());
        DeviceSession a = list.get(0);
        assertTrue(a.current);
        assertEquals("Pixel 8", a.name);
        assertEquals("", a.userAgent);
        assertEquals(1791376200000L, a.lastSeenAt);
        DeviceSession b = list.get(1);
        assertFalse(b.current);
        assertEquals("", b.name);
        assertEquals("Mozilla", b.userAgent);
        assertEquals(0L, b.createdAt);
        assertTrue(b.lastSeenAt > 0);
    }

    @Test
    public void safeIds_rejectPathTricks() {
        assertTrue(AccountApi.isSafeId("3f2a9c1e-0b7d-4c55-9a11-0123456789ab"));
        assertFalse(AccountApi.isSafeId("../x"));
        assertFalse(AccountApi.isSafeId("a/b"));
        assertFalse(AccountApi.isSafeId(""));
        assertFalse(AccountApi.isSafeId(null));
    }
}
