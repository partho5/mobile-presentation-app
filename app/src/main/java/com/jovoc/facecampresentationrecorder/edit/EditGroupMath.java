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

    private EditGroupMath() {}

    public static boolean isCropName(String fileName) {
        return fileName != null && CROP_NAME.matcher(fileName).find();
    }

    /** The first existing group member that is not a crop, or null when the master is gone. */
    public static String masterOf(List<String> groupPaths) {
        for (String path : groupPaths) {
            File f = new File(path);
            if (!isCropName(f.getName()) && f.isFile() && f.length() > 0) return path;
        }
        return null;
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
}
