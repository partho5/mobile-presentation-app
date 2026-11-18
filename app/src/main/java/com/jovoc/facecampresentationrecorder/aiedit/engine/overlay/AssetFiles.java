package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import java.io.File;

/**
 * Where a plan asset lives on the phone: {@code <assetsDir>/<id>}, one file per id, downloaded once. The id comes
 * from the server, so it is turned into a safe single file name (no path parts). Pure; no Android classes.
 */
public final class AssetFiles {

    private static final int MAX_NAME = 100;

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
}
