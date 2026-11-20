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
     * The brand a job is made in, from what is stored and without the network: the job's own brand when
     * it has one (a job keeps its brand after a switch), else the stored pick. Null when neither is set:
     * the brand must then be resolved off the main thread before the job is saved, or the job is not saved.
     */
    public static String jobBrand(String ownBrand, String storedPick) {
        if (!isEmpty(ownBrand)) return ownBrand;
        return isEmpty(storedPick) ? null : storedPick;
    }

    /**
     * The brand an auto-publish job of a new recording is made in, or null when it must not start: no
     * brand is picked yet, or the picked brand has lost access. Auto-publish stays off until a brand is chosen.
     */
    public static String autoPublishBrand(String storedPick, boolean accessLost) {
        if (accessLost || isEmpty(storedPick)) return null;
        return storedPick;
    }

    /**
     * The brand name to show for a job that failed because its brand lost access: the name when that brand is
     * the one in use and has a name, else null (the reason then reads without a name).
     */
    public static String lostBrandName(String jobBrand, String pickedId, String pickedName) {
        if (isEmpty(jobBrand) || !jobBrand.equals(pickedId)) return null;
        return isEmpty(pickedName) || pickedName.trim().isEmpty() ? null : pickedName;
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
