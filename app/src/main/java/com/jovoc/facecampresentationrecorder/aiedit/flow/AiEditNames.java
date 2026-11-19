package com.jovoc.facecampresentationrecorder.aiedit.flow;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File names of an AI edit's results. An output is {@code <base>_aiedit_<w>x<h>.mp4} (a second result of the same
 * recording {@code <base>_aiedit_2_<w>x<h>.mp4}), so it carries the crop name the recordings list already reads for
 * the ratio tag and for the group, and never matches the manual editor's {@code _edit} names. The plan is kept
 * in the app's own folder (see {@link #planFile}): MediaProvider refuses to create anything but video files under
 * DCIM, so a ".plan.json" (or its temp file) beside the outputs fails with EPERM. Pure; no Android classes.
 */
public final class AiEditNames {

    private static final Pattern AI_EDITED = Pattern.compile("_aiedit(_\\d+)?(_(9x16|4x5|1x1))?\\.mp4$");

    private AiEditNames() {}

    /** True for an AI edit's output, so the card can say "AI edit". */
    public static boolean isAiEditedName(String fileName) {
        return fileName != null && AI_EDITED.matcher(fileName).find();
    }

    /** "9:16" gives "9x16" (the crop name of the recordings list). */
    public static String ratioSuffix(String ratio) {
        return ratio.replace(':', 'x');
    }

    /** The output of {@code ratio} for the results under {@code outputBase}. */
    public static File outputFile(String outputBase, String ratio) {
        return new File(outputBase + "_" + ratioSuffix(ratio) + ".mp4");
    }

    /** The folder of the plan JSONs inside the app's files directory. */
    public static File plansDir(File filesDir) {
        return new File(filesDir, "aiedit_plans");
    }

    /**
     * The plan JSON of the results under {@code outputBase}, in {@code plansDir} (the app's own storage, never
     * DCIM). The hash of the full base keeps two recordings of the same name in different folders apart.
     */
    public static File planFile(File plansDir, String outputBase) {
        String name = new File(outputBase).getName();
        return new File(plansDir, name + "." + Integer.toHexString(outputBase.hashCode()) + ".plan.json");
    }

    /**
     * The first base beside {@code source} none of whose files exists yet for {@code ratios}:
     * {@code <source>_aiedit}, then {@code <source>_aiedit_2}, ...
     */
    public static String nextOutputBase(File source, List<String> ratios, File plansDir) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        File dir = source.getParentFile();
        for (int n = 1; ; n++) {
            String base = new File(dir, stem + "_aiedit" + (n > 1 ? "_" + n : "")).getPath();
            boolean taken = planFile(plansDir, base).exists();
            for (String ratio : ratios) taken |= outputFile(base, ratio).exists();
            if (!taken) return base;
        }
    }
}
