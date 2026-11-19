package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import java.io.File;

/**
 * Where a plan asset lives on the phone: {@code <assetsDir>/<id>}, one file per id, downloaded once. The id comes
 * from the server, so it is turned into a safe single file name (no path parts). Pure; no Android classes.
 */
public final class AssetFiles {

    private static final int MAX_NAME = 100;
    /** The frame rate of the keyed frames of a video asset; a clip lasts frameCount / FRAME_FPS. */
    public static final int FRAME_FPS = 24;
    private static final String FRAMES_SUFFIX = ".frames";

    private AssetFiles() {}

    /** The id as a file name: letters, digits, dot, dash and underscore kept, the rest replaced; never a path. */
    public static String safeName(String id) {
        StringBuilder sb = new StringBuilder(Math.min(id.length(), MAX_NAME));
        boolean changed = id.length() > MAX_NAME;
        for (int i = 0; i < id.length() && sb.length() < MAX_NAME; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (ok) {
                sb.append(c);
            } else {
                sb.append('_');
                changed = true;
            }
        }
        String name = sb.toString();
        // Only dots would name the folder itself or its parent
        if (name.isEmpty() || name.replace(".", "").isEmpty()) {
            name = "a_" + name;
            changed = true;
        }
        // A name that looks like a frames folder or its part folder must not collide with one
        if (name.endsWith(FRAMES_SUFFIX) || name.endsWith(".part")) changed = true;
        // Two different ids that clean to the same name must not share a file
        return changed ? name + "-" + Integer.toHexString(id.hashCode()) : name;
    }

    /** The file of asset {@code id} under {@code dir}. */
    public static File fileFor(File dir, String id) {
        return new File(dir, safeName(id));
    }

    /** The file of asset {@code id} when it is on the phone and not empty, else null. */
    public static File existing(File dir, String id) {
        if (dir == null || id == null) return null;
        File f = fileFor(dir, id);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** The folder of the keyed PNG frames of video asset {@code id} under {@code dir}. */
    public static File framesDir(File dir, String id) {
        return new File(dir, safeName(id) + FRAMES_SUFFIX);
    }

    /** The frames folder of asset {@code id} when it holds at least one PNG, else null. */
    public static File existingFrames(File dir, String id) {
        if (dir == null || id == null) return null;
        File f = framesDir(dir, id);
        File[] png = f.isDirectory() ? f.listFiles((d, n) -> n.endsWith(".png")) : null;
        return png != null && png.length > 0 ? f : null;
    }

    /** Whether {@code name} is the frames folder of another entry of the assets folder. */
    public static boolean isFramesName(String name) {
        return name.endsWith(FRAMES_SUFFIX);
    }

    /** The size in bytes of a file, or of everything under a folder. */
    public static long sizeOf(File f) {
        if (!f.isDirectory()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) total += sizeOf(c);
        return total;
    }

    /** Deletes a file, or a folder with everything under it; false when something is left. */
    public static boolean deleteTree(File f) {
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteTree(c);
        return f.delete();
    }
}
