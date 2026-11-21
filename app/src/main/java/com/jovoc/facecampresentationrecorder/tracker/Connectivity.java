package com.jovoc.facecampresentationrecorder.tracker;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Whether the phone has a network that Android has checked reaches the internet. */
final class Connectivity {

    private Connectivity() { }

    /** True only with both INTERNET and VALIDATED. Any exception counts as no internet. */
    static boolean hasInternet(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return false;
        }
    }
}
