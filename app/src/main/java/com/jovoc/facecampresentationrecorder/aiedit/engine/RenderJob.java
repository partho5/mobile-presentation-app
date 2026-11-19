package com.jovoc.facecampresentationrecorder.aiedit.engine;

import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One output file to render: the plan applied to the source for one ratio. The source is the file the plan was
 * made for (the master, or the manual editor's result), since cut times are on its timeline; it is only read.
 */
public final class RenderJob {

    public final File source;
    public final Plan plan;
    public final String ratio;
    public final File output;
    /** Where the plan's downloaded assets live; may be null while the plan names none. */
    public final File assetsDir;
    /** Draws the captions; null renders without captions. */
    public final CaptionPainter captionPainter;
    /** Set from any thread to stop the render; the partial file is deleted. */
    public final AtomicBoolean cancel;
    /** The app's own folder for the render's temporary files; null puts them beside the output. */
    public final File tempDir;

    public RenderJob(File source, Plan plan, String ratio, File output, File assetsDir,
                     CaptionPainter captionPainter, AtomicBoolean cancel) {
        this(source, plan, ratio, output, assetsDir, captionPainter, cancel, null);
    }

    public RenderJob(File source, Plan plan, String ratio, File output, File assetsDir,
                     CaptionPainter captionPainter, AtomicBoolean cancel, File tempDir) {
        if (source == null || plan == null || ratio == null || output == null) {
            throw new IllegalArgumentException("source, plan, ratio and output are required");
        }
        this.source = source;
        this.plan = plan;
        this.ratio = ratio;
        this.output = output;
        this.assetsDir = assetsDir;
        this.captionPainter = captionPainter;
        this.cancel = cancel != null ? cancel : new AtomicBoolean(false);
        this.tempDir = tempDir;
    }

    public boolean isCancelled() {
        return cancel.get();
    }
}
