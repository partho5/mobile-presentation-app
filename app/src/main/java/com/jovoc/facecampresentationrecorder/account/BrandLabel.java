package com.jovoc.facecampresentationrecorder.account;

import java.util.List;

/**
 * Which brand a message about a job names. With one brand nothing is named, so the messages stay as
 * they were; with several, a job's message names the job's own brand, never the brand in use now. Plain
 * Java with no Android types, so the rules are tested on the JVM; the stored list is read by
 * {@code PublishLabels}.
 */
public final class BrandLabel {

    private BrandLabel() {
    }

    /**
     * The name to show for a job made in {@code jobBrandId}, or null when nothing should be named: the
     * person has one brand (or none known), the job has no brand, the brand is not in the list, or it has
     * no name of its own. A missing name is never replaced by the id.
     */
    public static String brandName(List<Workspace> brands, String jobBrandId) {
        if (brands == null || brands.size() < 2 || isEmpty(jobBrandId)) return null;
        for (Workspace w : brands) {
            if (!jobBrandId.equals(w.id)) continue;
            String name = w.name == null ? "" : w.name.trim();
            return name.isEmpty() || name.equals(w.id) ? null : name;
        }
        return null;
    }

    /**
     * {@code text} with the brand added after its first line ("Failed: reason · Acme"), or {@code text}
     * unchanged when there is no brand to add or no text.
     */
    public static String withBrand(String text, String brandName) {
        if (text == null || brandName == null) return text;
        int cut = text.indexOf('\n');
        if (cut < 0) return text + " · " + brandName;
        return text.substring(0, cut) + " · " + brandName + text.substring(cut);
    }

    /**
     * True when a job was made in a brand other than the one picked now. Such a job's notification must not
     * open a screen of the picked brand (upgrade, set up, credits): it opens the job's own status instead.
     */
    public static boolean isOtherBrand(String jobBrandId, String pickedId) {
        return !isEmpty(jobBrandId) && !isEmpty(pickedId) && !jobBrandId.equals(pickedId);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
