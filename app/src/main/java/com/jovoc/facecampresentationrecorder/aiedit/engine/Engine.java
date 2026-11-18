package com.jovoc.facecampresentationrecorder.aiedit.engine;

import android.util.Log;

import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.LayeredPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.EffectFactory;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.render.MediaProbe;
import com.jovoc.facecampresentationrecorder.aiedit.engine.render.RenderPipeline;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextOverlayPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextPlacement;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The AI edit renderer's front door (spec section 19). {@link #render} writes a finished MP4 per ratio from a plan:
 * cuts and speed segments, the top-anchored crop of each ratio at the contract's output size and bitrate, and the
 * captions; {@link #renderProxy} writes the small proxy piece the server looks at (Standard, Heavy).
 *
 * Each output is a new file; the source is only read. Blocking: call from a worker thread (the job's foreground
 * service). A cancelled job throws {@link CancellationException} and leaves no partial file.
 */
public final class Engine {

    private static final String TAG = "AiEditEngine";
    /** The plan's own length and the timeline's may differ by rounding only; more is worth a log line. */
    private static final long DURATION_DRIFT_MS = 100;

    /** Short side of a proxy piece. */
    public static final int PROXY_SHORT_SIDE = 360;
    public static final int PROXY_FPS = 15;
    public static final int PROXY_VIDEO_BPS = 600_000;
    public static final int PROXY_AUDIO_RATE = 44_100;
    public static final int PROXY_AUDIO_BPS = 32_000;
    /** Longest proxy piece the server accepts. */
    public static final long PROXY_MAX_MS = 60_000;

    private final EngineContract contract;

    public Engine(EngineContract contract) {
        if (contract == null) throw new IllegalArgumentException("contract is required");
        this.contract = contract;
    }

    /** The jobs in render order: 9:16 first, then the contract's order (4:5, 1:1), unknown ratios last. */
    public static List<RenderJob> inRenderOrder(List<RenderJob> jobs) {
        List<RenderJob> sorted = new ArrayList<>(jobs);
        final List<String> order = Arrays.asList(EngineContract.OUTPUT_ORDER);
        Collections.sort(sorted, (a, b) -> Integer.compare(rank(order, a.ratio), rank(order, b.ratio)));
        return sorted;
    }

    private static int rank(List<String> order, String ratio) {
        int i = order.indexOf(ratio);
        return i < 0 ? order.size() : i;
    }

    /**
     * Renders every job, 9:16 first; {@link Listener#onFinished} fires for each file as soon as it is ready, so
     * the 9:16 result can be shown while the others render. Stops at the first failure or cancel; files already
     * finished stay.
     */
    public void render(List<RenderJob> jobs, Listener listener) throws IOException {
        for (RenderJob job : inRenderOrder(jobs)) {
            if (job.isCancelled()) throw new CancellationException("Render cancelled");
            render(job, listener);
        }
    }

    /** Renders one job to {@code job.output}. */
    public void render(final RenderJob job, final Listener listener) throws IOException {
        OutputSpec spec = contract.output(job.ratio);
        if (spec == null) throw new IllegalArgumentException("Unknown ratio " + job.ratio);
        MediaProbe probe = MediaProbe.of(job.source);
        Plan plan = job.plan;
        OutputTimeline timeline = OutputTimeline.fromPlan(plan, probe.durationMs);
        if (Math.abs(timeline.outputDurationMs() - plan.outputDurationMs) > DURATION_DRIFT_MS) {
            Log.w(TAG, "Plan length " + plan.outputDurationMs + " ms, timeline " + timeline.outputDurationMs() + " ms");
        }
        CropRect crop = OutputTimeline.cropFor(job.ratio, probe.width, probe.height);
        new RenderPipeline(job.source, probe, timeline, crop, spec.width, spec.height)
                .video(spec.bitrate, probe.frameRate)
                .withEffects(EffectFactory.videoEffects(plan, contract))
                .withPainter(painterFor(job, spec, timeline, crop, probe))
                .withCancelFlag(job.cancel)
                .withProgress(listener == null ? null : percent -> listener.onProgress(job, percent))
                .render(job.output);
        if (listener != null) listener.onFinished(job);
    }

    /** The captions of the job with the plan's text overlays on top; null when there is nothing to draw. */
    private CaptionPainter painterFor(RenderJob job, OutputSpec spec, OutputTimeline timeline, CropRect crop,
                                      MediaProbe probe) {
        float captionSize = job.captionPainter != null
                ? job.captionPainter.captionTextSizePx(spec.width, spec.height)
                : spec.width * CaptionPainter.DEFAULT_TEXT_SIZE_SHARE;
        TextOverlayPainter overlays = new TextOverlayPainter(job.plan, contract, spec, spec.width, spec.height,
                captionSize, facecamKeepout(timeline, crop, probe, spec));
        if (overlays.overlayCount() == 0) return job.captionPainter;
        return new LayeredPainter(overlays, job.captionPainter);
    }

    /** The facecam circle in output pixels: the master's fractions through the top-anchored crop. */
    private static TextPlacement.Keepout facecamKeepout(final OutputTimeline timeline, final CropRect crop,
                                                        final MediaProbe probe, final OutputSpec spec) {
        final double cropX = crop != null ? crop.x : 0;
        final double cropW = crop != null ? crop.w : probe.width;
        return (outputMs, out) -> {
            Plan.FacecamKey key = timeline.facecamAt(outputMs);
            if (key == null) return false;
            double scale = spec.width / cropW;
            out[0] = (float) ((key.cx * probe.width - cropX) * scale);
            out[1] = (float) (key.cy * probe.height * scale);
            out[2] = (float) (key.radius * probe.width * scale);
            return true;
        };
    }

    /** {@link #renderProxy(File, long, long, File, AtomicBoolean)} that cannot be cancelled. */
    public static void renderProxy(File source, long startMs, long endMs, File out) throws IOException {
        renderProxy(source, startMs, endMs, out, null);
    }

    /**
     * Writes source {@code [startMs, endMs)} (at most {@link #PROXY_MAX_MS}) as a small proxy: the whole frame
     * scaled to a short side of 360, 15 fps, ~600 kbps H.264, mono AAC 32 kbps.
     */
    public static void renderProxy(File source, long startMs, long endMs, File out, AtomicBoolean cancel)
            throws IOException {
        if (startMs < 0 || endMs <= startMs || endMs - startMs > PROXY_MAX_MS) {
            throw new IllegalArgumentException("Proxy piece must be 1 ms to 60 s");
        }
        MediaProbe probe = MediaProbe.of(source);
        long end = Math.min(endMs, probe.durationMs);
        List<Plan.Cut> cuts = new ArrayList<>();
        if (startMs > 0) cuts.add(new Plan.Cut(0, startMs, "proxy"));
        if (end < probe.durationMs) cuts.add(new Plan.Cut(end, probe.durationMs, "proxy"));
        OutputTimeline timeline = new OutputTimeline(probe.durationMs, cuts,
                Collections.<Plan.SpeedSegment>emptyList(), Collections.<Plan.FacecamKey>emptyList());
        int[] size = proxySize(probe.width, probe.height);
        new RenderPipeline(source, probe, timeline, null, size[0], size[1])
                .video(PROXY_VIDEO_BPS, Math.min(PROXY_FPS, probe.frameRate))
                .audio(PROXY_AUDIO_RATE, 1, PROXY_AUDIO_BPS)
                .withCancelFlag(cancel)
                .render(out);
    }

    /** The proxy size of a {@code w} × {@code h} source: short side 360 (never upscaled), even, aspect kept. */
    public static int[] proxySize(int w, int h) {
        int shortSide = Math.min(w, h);
        double scale = shortSide > PROXY_SHORT_SIDE ? (double) PROXY_SHORT_SIDE / shortSide : 1.0;
        int pw = (int) Math.round(w * scale) & ~1;
        int ph = (int) Math.round(h * scale) & ~1;
        return new int[]{Math.max(2, pw), Math.max(2, ph)};
    }
}
