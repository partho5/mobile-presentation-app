package com.jovoc.facecampresentationrecorder.activitylog;

import android.content.Context;
import android.media.MediaDrm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The anonymous id of this phone for the activity log: a UUID made from the MediaDrm (Widevine)
 * device-unique id. The raw id is never kept or sent, only its salted SHA-256. Without Widevine
 * (emulators, some devices) a random UUID is used instead. Either way it is stored once, so it
 * stays the same from then on. The backend links the rows of this id to the account at sign-in.
 */
public final class DeviceId {

    private static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);
    private static final String SALT = "facecam-activity-v1";

    private static volatile String cached;

    private DeviceId() {
    }

    /** The id of this phone; opens MediaDrm on the first call only, so call it off the main thread. */
    public static String get(Context context) {
        String id = cached;
        if (id != null) return id;
        synchronized (DeviceId.class) {
            if (cached != null) return cached;
            ActivityLogPrefs prefs = new ActivityLogPrefs(context);
            String saved = prefs.deviceId();
            if (saved == null) {
                byte[] raw = widevineId();
                saved = raw != null ? derive(raw) : UUID.randomUUID().toString();
                prefs.setDeviceId(saved);
            }
            cached = saved;
            return saved;
        }
    }

    /** The Widevine device-unique id, or null when this phone has none or MediaDrm refuses. */
    private static byte[] widevineId() {
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(WIDEVINE);
            byte[] id = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            return id == null || id.length == 0 ? null : id;
        } catch (Exception e) {
            return null;
        } finally {
            if (drm != null) {
                try {
                    drm.close();
                } catch (Exception ignored) {
                    // Nothing left to free.
                }
            }
        }
    }

    /**
     * A UUID (version 5 layout, RFC 4122 variant, so the backend's UUID check passes) from the
     * first 16 bytes of SHA-256(salt + raw id). The same input always gives the same UUID.
     */
    static String derive(byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SALT.getBytes(StandardCharsets.UTF_8));
            md.update(raw);
            byte[] h = md.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (h[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (h[i] & 0xff);
            return new UUID(msb, lsb).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}
