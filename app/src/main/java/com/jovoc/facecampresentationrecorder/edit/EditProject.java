package com.jovoc.facecampresentationrecorder.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What the user wants done to a recording: which stretches of the master to keep and what to do
 * with the sound. Immutable and free of Android classes, so the whole time maths is unit-testable;
 * every {@code with...} method returns a new project, which makes undo/redo a stack of snapshots.
 * The master file itself is never touched.
 *
 * All times are milliseconds on the master's own timeline. An empty {@link #getKeep keep} list
 * means "the whole file", so a fresh project needs no knowledge of the duration; the duration is
 * passed to the methods that need it.
 */
public final class EditProject {

    /** A stretch of the master, {@code startMs <= t < endMs}. */
    public static final class Range {
        public final long startMs;
        public final long endMs;

        public Range(long startMs, long endMs) {
            if (endMs < startMs) throw new IllegalArgumentException("end before start");
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long lengthMs() {
            return endMs - startMs;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Range)) return false;
            Range r = (Range) o;
            return startMs == r.startMs && endMs == r.endMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startMs, endMs);
        }

        @Override
        public String toString() {
            return "[" + startMs + "," + endMs + ")";
        }
    }

    /** What happens to the recording's own audio. Volume is a gain, 0..2 (1 = unchanged). */
    public static final class AudioSettings {
        public static final AudioSettings DEFAULT = new AudioSettings(1f, false, false);

        public final float volume;
        public final boolean muted;
        public final boolean removed;

        public AudioSettings(float volume, boolean muted, boolean removed) {
            this.volume = Math.max(0f, Math.min(2f, volume));
            this.muted = muted;
            this.removed = removed;
        }

        /** True when the original audio can be copied as it is. */
        public boolean isUnchanged() {
            return volume == 1f && !muted && !removed;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AudioSettings)) return false;
            AudioSettings a = (AudioSettings) o;
            return volume == a.volume && muted == a.muted && removed == a.removed;
        }

        @Override
        public int hashCode() {
            return Objects.hash(volume, muted, removed);
        }
    }

    /**
     * An audio file laid over the video. {@code sourceInMs..sourceOutMs} is the part of the file
     * used (out <= in means to its end); {@code startOnTimelineMs} is where it starts on the
     * edited result's timeline, after the cuts. It is clipped at the end of the video.
     */
    public static final class AddedAudio {
        public final String uri;
        public final long sourceInMs;
        public final long sourceOutMs;
        public final long startOnTimelineMs;
        public final float volume;

        public AddedAudio(String uri, long sourceInMs, long sourceOutMs, long startOnTimelineMs,
                          float volume) {
            this.uri = uri;
            this.sourceInMs = sourceInMs;
            this.sourceOutMs = sourceOutMs;
            this.startOnTimelineMs = startOnTimelineMs;
            this.volume = Math.max(0f, Math.min(2f, volume));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AddedAudio)) return false;
            AddedAudio a = (AddedAudio) o;
            return Objects.equals(uri, a.uri) && sourceInMs == a.sourceInMs
                    && sourceOutMs == a.sourceOutMs && startOnTimelineMs == a.startOnTimelineMs
                    && volume == a.volume;
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri, sourceInMs, sourceOutMs, startOnTimelineMs, volume);
        }
    }

    private final String masterPath;
    private final List<Range> keep;
    private final AudioSettings original;
    private final List<AddedAudio> added;

    public EditProject(String masterPath, List<Range> keep, AudioSettings original,
                       List<AddedAudio> added) {
        this.masterPath = masterPath;
        this.keep = Collections.unmodifiableList(new ArrayList<>(keep));
        this.original = original == null ? AudioSettings.DEFAULT : original;
        this.added = Collections.unmodifiableList(new ArrayList<>(added));
    }

    /** A project that keeps everything and changes nothing. */
    public static EditProject wholeFile(String masterPath) {
        return new EditProject(masterPath, Collections.<Range>emptyList(), AudioSettings.DEFAULT,
                Collections.<AddedAudio>emptyList());
    }

    public String getMasterPath() {
        return masterPath;
    }

    /** Kept ranges as stored (sorted, non-overlapping; may touch after a split). Empty = whole file. */
    public List<Range> getKeep() {
        return keep;
    }

    public AudioSettings getOriginal() {
        return original;
    }

    public List<AddedAudio> getAdded() {
        return added;
    }

    // ---------------------------------------------------------------- queries

    /**
     * The kept ranges clamped to {@code [0, durationMs]}, sorted, with empty ones dropped; the
     * whole file when nothing was cut. Ranges that touch stay separate (they are the segments the
     * editor shows after a split).
     */
    public List<Range> effectiveKeep(long durationMs) {
        if (keep.isEmpty()) {
            return durationMs > 0
                    ? Collections.singletonList(new Range(0, durationMs))
                    : Collections.<Range>emptyList();
        }
        return normalize(keep, durationMs);
    }

    /**
     * What the renderer plays through: {@link #effectiveKeep} with touching ranges joined, since a
     * split on its own cuts nothing and must not cause a seek or a seam.
     */
    public List<Range> renderRanges(long durationMs) {
        List<Range> out = new ArrayList<>();
        for (Range r : effectiveKeep(durationMs)) {
            Range last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.endMs >= r.startMs) {
                out.set(out.size() - 1, new Range(last.startMs, Math.max(last.endMs, r.endMs)));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /** Length of the result: what is left once the cuts are applied. */
    public long outputDurationMs(long durationMs) {
        long total = 0;
        for (Range r : effectiveKeep(durationMs)) total += r.lengthMs();
        return total;
    }

    /** True when some part of the master is dropped (a split alone is not a cut). */
    public boolean hasCuts(long durationMs) {
        List<Range> ranges = renderRanges(durationMs);
        return !(ranges.size() == 1 && ranges.get(0).startMs == 0
                && ranges.get(0).endMs == durationMs);
    }

    /** True when the audio needs decoding and mixing instead of a plain sample copy. */
    public boolean hasAudioChanges() {
        return !original.isUnchanged() || !added.isEmpty();
    }

    /** Position in the result to the matching position on the master; clamped to the result. */
    public long outputToSource(long outputMs, long durationMs) {
        List<Range> ranges = effectiveKeep(durationMs);
        if (ranges.isEmpty()) return 0;
        long into = Math.max(0, outputMs);
        for (Range r : ranges) {
            if (into < r.lengthMs()) return r.startMs + into;
            into -= r.lengthMs();
        }
        return ranges.get(ranges.size() - 1).endMs;
    }

    /** Position on the master to its place in the result, or -1 when that moment was cut. */
    public long sourceToOutput(long sourceMs, long durationMs) {
        long before = 0;
        for (Range r : effectiveKeep(durationMs)) {
            if (sourceMs >= r.startMs && sourceMs < r.endMs) return before + (sourceMs - r.startMs);
            before += r.lengthMs();
        }
        return -1;
    }

    // ---------------------------------------------------------------- edits

    /** Cuts the range containing {@code atMs} in two. A point on an existing boundary changes nothing. */
    public EditProject withSplit(long atMs, long durationMs) {
        List<Range> out = new ArrayList<>();
        for (Range r : effectiveKeep(durationMs)) {
            if (atMs > r.startMs && atMs < r.endMs) {
                out.add(new Range(r.startMs, atMs));
                out.add(new Range(atMs, r.endMs));
            } else {
                out.add(r);
            }
        }
        return withKeep(out);
    }

    /**
     * Removes {@code [fromMs, toMs)} from the result (a segment, or a trim of the start or end).
     * Throws when nothing would be left: the editor must keep Delete off for that case, because an
     * empty keep list means "the whole file".
     */
    public EditProject withDeleted(long fromMs, long toMs, long durationMs) {
        List<Range> out = new ArrayList<>();
        for (Range r : effectiveKeep(durationMs)) {
            if (toMs <= r.startMs || fromMs >= r.endMs) {
                out.add(r);
                continue;
            }
            if (fromMs > r.startMs) out.add(new Range(r.startMs, fromMs));
            if (toMs < r.endMs) out.add(new Range(toMs, r.endMs));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Nothing would be left");
        return withKeep(out);
    }

    /** Keeps only {@code [fromMs, toMs)}: both trim handles in one step. */
    public EditProject withTrim(long fromMs, long toMs, long durationMs) {
        List<Range> out = new ArrayList<>();
        for (Range r : effectiveKeep(durationMs)) {
            long s = Math.max(r.startMs, fromMs);
            long e = Math.min(r.endMs, toMs);
            if (e > s) out.add(new Range(s, e));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Nothing would be left");
        return withKeep(out);
    }

    public EditProject withOriginal(AudioSettings settings) {
        return new EditProject(masterPath, keep, settings, added);
    }

    public EditProject withAdded(List<AddedAudio> tracks) {
        return new EditProject(masterPath, keep, original, tracks);
    }

    private EditProject withKeep(List<Range> ranges) {
        return new EditProject(masterPath, ranges, original, added);
    }

    /** Sorted, clamped to {@code [0, durationMs]}, empty ranges dropped, overlapping ones merged. */
    static List<Range> normalize(List<Range> ranges, long durationMs) {
        List<Range> sorted = new ArrayList<>();
        for (Range r : ranges) {
            long s = Math.max(0, r.startMs);
            long e = Math.min(durationMs, r.endMs);
            if (e > s) sorted.add(new Range(s, e));
        }
        Collections.sort(sorted, (a, b) -> a.startMs != b.startMs
                ? Long.compare(a.startMs, b.startMs) : Long.compare(a.endMs, b.endMs));
        List<Range> out = new ArrayList<>();
        for (Range r : sorted) {
            Range last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r.startMs < last.endMs) {
                out.set(out.size() - 1, new Range(last.startMs, Math.max(last.endMs, r.endMs)));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EditProject)) return false;
        EditProject p = (EditProject) o;
        return Objects.equals(masterPath, p.masterPath) && keep.equals(p.keep)
                && original.equals(p.original) && added.equals(p.added);
    }

    @Override
    public int hashCode() {
        return Objects.hash(masterPath, keep, original, added);
    }
}
