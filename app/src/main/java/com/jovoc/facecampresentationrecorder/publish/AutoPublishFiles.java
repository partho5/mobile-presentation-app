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
        File crop = new File(VideoCropHelper.buildOutputPath(original.getAbsolutePath(), "9x16"));
        if (crop.isFile() && crop.length() > 0) return crop;
        return original;
    }
}
