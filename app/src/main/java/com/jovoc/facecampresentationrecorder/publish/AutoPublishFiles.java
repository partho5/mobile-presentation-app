package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;

import java.io.File;

/** Which file of a finished recording auto-publish uploads. */
public final class AutoPublishFiles {

    private AutoPublishFiles() {}

    /**
     * The 9:16 crop when one was made (and is complete), otherwise the processed original.
     * Short vertical video is what the channels want; the original is the safe fallback when
     * 9:16 is switched off or its crop failed.
     */
    public static File pick(File original) {
        return pick(original, null);
    }

    /**
     * Same, for a recording that was renamed while it was processed: the crop is written next to
     * the path processing started with ({@code processedAs}), so it is looked for under the
     * current name first, then under that one.
     */
    public static File pick(File original, File processedAs) {
        File crop = crop(original);
        if (crop != null) return crop;
        if (processedAs != null) {
            crop = crop(processedAs);
            if (crop != null) return crop;
        }
        return original;
    }

    private static File crop(File video) {
        File crop = new File(VideoCropHelper.buildOutputPath(video.getAbsolutePath(), "9x16"));
        return crop.isFile() && crop.length() > 0 ? crop : null;
    }
}
