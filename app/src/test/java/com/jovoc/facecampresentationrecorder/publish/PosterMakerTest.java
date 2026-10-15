package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.IntBinaryOperator;

/** The poster choices (frame, size, quality, size cap) with a fake imaging backend. */
public class PosterMakerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** An "image" is just its size; the jpeg size comes from a function of pixels and quality. */
    private static class Fake implements PosterMaker.Imaging<int[]> {
        long durationMs = 10_000;
        int frameW = 1080;
        int frameH = 1920;
        boolean noFrame;
        boolean throwOnGrab;
        /** (pixels, quality) -> jpeg byte count */
        IntBinaryOperator jpegSize = (px, q) -> 20_000;
        final List<Long> grabs = new ArrayList<>();
        final List<String> encoded = new ArrayList<>();
        int released;

        @Override
        public long durationMs(File video) {
            return durationMs;
        }

        @Override
        public int[] grab(File video, long timeUs) {
            grabs.add(timeUs);
            if (throwOnGrab) throw new IllegalArgumentException("bad file");
            return noFrame ? null : new int[]{frameW, frameH};
        }

        @Override
        public int width(int[] image) {
            return image[0];
        }

        @Override
        public int height(int[] image) {
            return image[1];
        }

        @Override
        public int[] scale(int[] image, int width, int height) {
            return new int[]{width, height};
        }

        @Override
        public byte[] jpeg(int[] image, int quality) {
            encoded.add(image[0] + "x" + image[1] + "@" + quality);
            byte[] b = new byte[jpegSize.applyAsInt(image[0] * image[1], quality)];
            b[0] = (byte) 0xFF;
            b[1] = (byte) 0xD8;
            b[2] = (byte) 0xFF;
            return b;
        }

        @Override
        public void release(int[] image) {
            released++;
        }
    }

    private File video() throws Exception {
        File f = tmp.newFile("v.mp4");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[16]);
        }
        return f;
    }

    // ---- scaling math ----

    @Test
    public void scaledSize_keepsTheAspectAndLimitsTheLongSide() {
        assertArrayEquals(new int[]{270, 480}, PosterMaker.scaledSize(1080, 1920, 480));
        assertArrayEquals(new int[]{480, 270}, PosterMaker.scaledSize(1920, 1080, 480));
        assertArrayEquals(new int[]{480, 480}, PosterMaker.scaledSize(1000, 1000, 480));
    }

    @Test
    public void scaledSize_neverScalesUpAndNeverReachesZero() {
        assertArrayEquals(new int[]{320, 240}, PosterMaker.scaledSize(320, 240, 480));
        assertArrayEquals(new int[]{1, 480}, PosterMaker.scaledSize(1, 5000, 480));
    }

    @Test
    public void frameTime_isOneSecondInOrTheMiddleOfAShortClip() {
        assertEquals(1_000_000L, PosterMaker.frameTimeUs(30_000));
        assertEquals(1_000_000L, PosterMaker.frameTimeUs(2_000));
        assertEquals(500_000L, PosterMaker.frameTimeUs(1_000));
        assertEquals(0L, PosterMaker.frameTimeUs(0));
        assertEquals(0L, PosterMaker.frameTimeUs(-1));
    }

    // ---- encoding ----

    @Test
    public void aSmallJpegAtFirstQualityIsKept() throws Exception {
        Fake f = new Fake();
        byte[] out = new PosterMaker<>(f).jpegFor(video());

        assertNotNull(out);
        assertEquals(20_000, out.length);
        assertEquals(List.of("270x480@80"), f.encoded);
        assertEquals(List.of(1_000_000L), f.grabs);
        assertEquals(2, f.released); // the frame and its scaled copy
    }

    @Test
    public void qualityStepsDownUntilTheAimIsMet() throws Exception {
        Fake f = new Fake();
        f.jpegSize = (px, q) -> q * 1000; // 80 KB, 65 KB, 50 KB
        byte[] out = new PosterMaker<>(f).jpegFor(video());

        assertNotNull(out);
        assertEquals(50_000, out.length);
        assertEquals(List.of("270x480@80", "270x480@65", "270x480@50"), f.encoded);
    }

    @Test
    public void whenTheAimIsMissedTheSmallestUnderTheCapIsSent() throws Exception {
        Fake f = new Fake();
        f.jpegSize = (px, q) -> 200_000 + q;
        byte[] out = new PosterMaker<>(f).jpegFor(video());

        assertNotNull(out);
        assertEquals(200_035, out.length);
        assertEquals(4, f.encoded.size()); // no shrink needed
    }

    @Test
    public void overTheHardCapTheImageShrinksAndNeverExceedsIt() throws Exception {
        Fake f = new Fake();
        // Big until the picture is under about 70 000 pixels (long side 360 -> 203x360).
        f.jpegSize = (px, q) -> px > 100_000 ? PosterMaker.MAX_BYTES + 1 : 40_000;
        byte[] out = new PosterMaker<>(f).jpegFor(video());

        assertNotNull(out);
        assertTrue(out.length <= PosterMaker.MAX_BYTES);
        assertTrue(f.encoded.contains("203x360@80"));
    }

    @Test
    public void ifNothingFitsTheCapTheResultIsNull() throws Exception {
        Fake f = new Fake();
        f.jpegSize = (px, q) -> PosterMaker.MAX_BYTES + 1;

        File v = video();
        assertNull(new PosterMaker<>(f).jpegFor(v));
        assertNull(new PosterMaker<>(f).base64For(v));
    }

    // ---- failures are null ----

    @Test
    public void noFrame_isNullAfterTryingTheStart() throws Exception {
        Fake f = new Fake();
        f.noFrame = true;

        assertNull(new PosterMaker<>(f).base64For(video()));
        assertEquals(List.of(1_000_000L, 0L), f.grabs);
    }

    @Test
    public void aThrowingBackend_isNull() throws Exception {
        Fake f = new Fake();
        f.throwOnGrab = true;

        assertNull(new PosterMaker<>(f).base64For(video()));
    }

    @Test
    public void aMissingOrEmptyFile_isNull() throws Exception {
        PosterMaker<int[]> m = new PosterMaker<>(new Fake());

        assertNull(m.base64For(new File(tmp.getRoot(), "none.mp4")));
        assertNull(m.base64For(tmp.newFile("empty.mp4")));
        assertNull(m.base64For(null));
    }

    @Test
    public void bytesThatAreNotAJpegAreRefusedBeforeTheServerSeesThem() throws Exception {
        Fake f = new Fake() {
            @Override
            public byte[] jpeg(int[] image, int quality) {
                return new byte[100];
            }
        };

        assertNull(new PosterMaker<>(f).base64For(video()));
    }

    @Test
    public void base64_isPlainPaddedBase64OfTheJpeg() throws Exception {
        Fake f = new Fake();
        f.jpegSize = (px, q) -> 10;

        String b64 = new PosterMaker<>(f).base64For(video());

        assertNotNull(b64);
        assertEquals(0, b64.length() % 4);
        byte[] raw = Base64.getDecoder().decode(b64);
        assertEquals(10, raw.length);
        assertTrue(PosterMaker.isJpeg(raw));
    }

    @Test
    public void aSmallFrameIsNotScaledUp() throws Exception {
        Fake f = new Fake();
        f.frameW = 300;
        f.frameH = 200;
        new PosterMaker<>(f).jpegFor(video());

        assertEquals(List.of("300x200@80"), f.encoded);
    }
}
