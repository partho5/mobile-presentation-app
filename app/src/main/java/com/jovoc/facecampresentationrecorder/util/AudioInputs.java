package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jovoc.facecampresentationrecorder.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Audio inputs the recorder can use: the phone mic plus any wired headset / USB microphone.
 * Bluetooth headset mics are deliberately not offered: routing them needs a call-quality SCO link
 * (8-16 kHz, switches the whole phone's audio mode, often fails to connect from a service), which
 * is worse than the phone mic for narration.
 *
 * <p>The choice lives in memory only: a mic that is connected when the app starts (or later)
 * is used by default, as the user expects. If the chosen mic disappears the system routes the
 * recording back to the phone mic by itself; nothing here reacts during a recording.
 */
public final class AudioInputs {

    private static final AudioInputSelection SELECTION = new AudioInputSelection();
    private static boolean announced;
    private static Set<Integer> knownExternalIds = new HashSet<>();

    private AudioInputs() {}

    /** Connected external microphones (wired headset, USB), in the order the system reports them. */
    public static List<AudioDeviceInfo> externalInputs(Context context) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return result;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (isExternal(d)) result.add(d);
        }
        return result;
    }

    private static boolean isExternal(AudioDeviceInfo d) {
        int type = d.getType();
        return d.isSource() && (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET);
    }

    /** The mic to route the recording to; null leaves the choice to the system. */
    @Nullable
    public static AudioDeviceInfo resolveDevice(Context context) {
        List<AudioDeviceInfo> external = externalInputs(context);
        // Without an external mic there is nothing to choose between: system default
        if (external.isEmpty()) return null;
        int id = SELECTION.resolve(ids(external));
        if (id != AudioInputSelection.PHONE_MIC) {
            for (AudioDeviceInfo d : external) {
                if (d.getId() == id) return d;
            }
        }
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return d;
        }
        return null;
    }

    /** Id of the external mic that will be used, or {@link AudioInputSelection#PHONE_MIC}. */
    public static int selectedId(Context context) {
        return SELECTION.resolve(ids(externalInputs(context)));
    }

    public static void selectPhoneMic() {
        SELECTION.selectPhoneMic();
    }

    public static void selectExternal(int deviceId) {
        SELECTION.selectExternal(deviceId);
    }

    /** Short name for the list and the toast. */
    public static String label(Context context, AudioDeviceInfo device) {
        if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            // The product name of a 3.5 mm jack is just the phone's model
            return context.getString(R.string.audio_src_wired);
        }
        CharSequence name = device.getProductName();
        if (name != null && name.toString().trim().length() > 0) {
            return context.getString(R.string.audio_src_usb_named, name.toString().trim());
        }
        return context.getString(R.string.audio_src_usb);
    }

    /** True once per app process: the "external microphone connected" toast is shown at most once. */
    public static synchronized boolean claimAnnouncement() {
        if (announced) return false;
        announced = true;
        return true;
    }

    /**
     * Calls {@code onChange} on the main thread whenever inputs appear or disappear (and once on
     * registration). An external mic that was not seen before becomes the default choice again;
     * re-registering for a mic that is already known (activity restart) keeps the user's choice.
     */
    public static AudioDeviceCallback register(Context context, Runnable onChange) {
        Context app = context.getApplicationContext();
        AudioManager am = app.getSystemService(AudioManager.class);
        AudioDeviceCallback callback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                noteDevices(app);
                onChange.run();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                noteDevices(app);
                onChange.run();
            }
        };
        if (am != null) am.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
        return callback;
    }

    private static synchronized void noteDevices(Context context) {
        Set<Integer> current = new HashSet<>();
        for (AudioDeviceInfo d : externalInputs(context)) current.add(d.getId());
        if (!knownExternalIds.containsAll(current)) SELECTION.onExternalConnected();
        knownExternalIds = current;
    }

    public static void unregister(Context context, @NonNull AudioDeviceCallback callback) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am != null) am.unregisterAudioDeviceCallback(callback);
    }

    private static int[] ids(List<AudioDeviceInfo> devices) {
        int[] ids = new int[devices.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = devices.get(i).getId();
        return ids;
    }
}
