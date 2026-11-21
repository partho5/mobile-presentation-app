package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;
import android.media.MediaDrm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * The device id of the activity tracker: a SHA-256 of the MediaDrm unique id with an app salt, so the
 * raw id is never stored or sent. Not the advertising id. Falls back to a random UUID.
 */
public final class DeviceId {

    private static final String SALT = "facecam-tracker-v1";
    private static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    private DeviceId() { }

    /** Deterministic UUID string (version 5 layout) derived from {@code raw}. Pure Java. */
    public static String derive(byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SALT.getBytes(StandardCharsets.UTF_8));
            md.update(raw == null ? new byte[0] : raw);
            byte[] b = Arrays.copyOf(md.digest(), 16);
            b[6] = (byte) ((b[6] & 0x0f) | 0x50);
            b[8] = (byte) ((b[8] & 0x3f) | 0x80);
            StringBuilder sb = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
                sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The stored device id, creating and saving it on first use. Call only off the main thread:
     * MediaDrm can be slow.
     */
    public static String get(Context context) {
        TrackerPrefs prefs = new TrackerPrefs(context);
        String cached = prefs.deviceId();
        if (cached != null) return cached;

        String id = null;
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(WIDEVINE);
            id = derive(drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID));
        } catch (Throwable ignored) {
            id = null;
        } finally {
            if (drm != null) {
                try {
                    drm.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
        if (id == null) id = UUID.randomUUID().toString();
        prefs.setDeviceId(id);
        return id;
    }
}
