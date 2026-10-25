package com.jovoc.facecampresentationrecorder.edit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers over the paths of one recording group (master plus its aspect-ratio crops): which
 * file is the master, and which crop ratios the group had, so an edit can make the same set.
 */
public final class EditGroupMath {

    /** A crop is named {@code <master>_<w>x<h>.mp4} (see VideoCropHelper.buildOutputPath).
     * Only the three ratios the app produces count, so a master renamed to e.g. "talk_16x9" is not
     * mistaken for a crop. */
    private static final Pattern CROP_NAME = Pattern.compile("_(9x16|4x5|1x1)\\.mp4$");

    /** An edit is named {@code <name>_edit.mp4} or {@code <name>_edit_<n>.mp4}, with a crop ratio
     * after it for the edit's own crops (see EditTimelineMath.nextOutputFile). */
    private static final Pattern EDITED_NAME =
            Pattern.compile("_edit(_\\d+)?(_(9x16|4x5|1x1))?\\.mp4$");

    private EditGroupMath() {}

    /** True for an edited video or one of its crops, so the card can say "Edited". */
    public static boolean isEditedName(String fileName) {
        return fileName != null && EDITED_NAME.matcher(fileName).find();
    }

    public static boolean isCropName(String fileName) {
        return fileName != null && CROP_NAME.matcher(fileName).find();
    }

    /**
     * The group's master: the first existing member that is neither a crop nor an edit, so an
     * edit never becomes the master while the unedited video is there. When only edits are left,
     * the first of them. Null when no such file exists.
     */
    public static String masterOf(List<String> groupPaths) {
        String edited = null;
        for (String path : groupPaths) {
            File f = new File(path);
            if (isCropName(f.getName()) || !f.isFile() || f.length() == 0) continue;
            if (!isEditedName(f.getName())) return path;
            if (edited == null) edited = path;
        }
        return edited;
    }

    /** Crop ratios named by the group's files, 9:16 first, then 4:5, then 1:1. Others are ignored. */
    public static List<int[]> cropRatiosOf(List<String> groupPaths) {
        boolean r916 = false, r45 = false, r11 = false;
        for (String path : groupPaths) {
            Matcher m = CROP_NAME.matcher(new File(path).getName());
            if (!m.find()) continue;
            String ratio = m.group(1);
            if (ratio.equals("9x16")) r916 = true;
            else if (ratio.equals("4x5")) r45 = true;
            else if (ratio.equals("1x1")) r11 = true;
        }
        List<int[]> out = new ArrayList<>();
        if (r916) out.add(new int[]{9, 16});
        if (r45) out.add(new int[]{4, 5});
        if (r11) out.add(new int[]{1, 1});
        return out;
    }

    /**
     * The files "Delete original?" may remove: the master that was edited and its own crops
     * ({@code <master>_<ratio>.mp4}), nothing else. The edit, its crops and any other edit share the
     * group but are never offered, and {@code output} is excluded as a last guard.
     */
    public static List<String> deletablePaths(List<String> group, String master, String output) {
        String masterName = new File(master).getName();
        int dot = masterName.lastIndexOf('.');
        String base = dot > 0 ? masterName.substring(0, dot) : masterName;
        Pattern cropOfMaster = Pattern.compile(
                Pattern.quote(base) + "_(9x16|4x5|1x1)\\.mp4$");
        List<String> out = new ArrayList<>();
        for (String path : group) {
            if (path.equals(output) || out.contains(path)) continue;
            String name = new File(path).getName();
            if (path.equals(master) || cropOfMaster.matcher(name).matches()) out.add(path);
        }
        return out;
    }
}
