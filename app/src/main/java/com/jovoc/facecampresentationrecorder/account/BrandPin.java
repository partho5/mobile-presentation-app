package com.jovoc.facecampresentationrecorder.account;

import com.jovoc.facecampresentationrecorder.auth.TokenStore;

import java.util.List;

/**
 * Which brand this phone works in when it has not picked one: the server's default, saved as the
 * pick so nothing ever relies on "the server's default" later. The rules are plain Java with no
 * Android types, so they are tested on the JVM; {@link #resolve} blocks and is called off the main
 * thread (sign-in, startup, or the first authed call with no pick).
 */
public final class BrandPin {

    private static final Object LOCK = new Object();

    private BrandPin() {
    }

    /**
     * Whether the server's default should be saved as the pick: at sign-in always (it replaces an
     * earlier pick), otherwise only while no pick is stored.
     */
    public static boolean shouldPin(String storedId, boolean atSignIn) {
        return atSignIn || isEmpty(storedId);
    }

    /** The name of the brand with this id in the list, or "" when it is not there. */
    public static String nameOf(List<Workspace> brands, String id) {
        if (brands != null && id != null) {
            for (Workspace w : brands) {
                if (id.equals(w.id)) return w.name == null ? "" : w.name;
            }
        }
        return "";
    }

    /**
     * Returns the brand in use and, when {@link #shouldPin} says so, saves the server's default as the
     * pick first. Nothing is saved when the default cannot be read, so the pick stays as it was.
     * Blocking; never called from the main thread.
     */
    public static String resolve(AccountApi api, TokenStore store, boolean atSignIn) throws AccountException {
        synchronized (LOCK) {
            String stored = store.workspaceId();
            if (!shouldPin(stored, atSignIn)) return stored;
            String id = api.serverDefaultWorkspaceId();
            String name = nameOf(api.listWorkspaces(), id);
            store.saveWorkspace(id, name);
            return id;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
