package com.jovoc.facecampresentationrecorder.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class Mp4VideoSizeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rewritesVideoTrackSizeAndNothingElse() throws IOException {
        byte[] original = mp4(track("mp4a", 0, 0), track("avc1", 1080, 2340));
        File file = write(original);

        assertTrue(Mp4VideoSize.set(file, 1080, 1920));

        byte[] patched = Files.readAllBytes(file.toPath());
        assertArrayEquals(mp4(track("mp4a", 0, 0), track("avc1", 1080, 1920)), patched);
        assertEquals(original.length, patched.length);
    }

    @Test
    public void leavesFileWithoutH264TrackUntouched() throws IOException {
        byte[] original = mp4(track("mp4a", 0, 0), track("hvc1", 1080, 2340));
        File file = write(original);

        assertFalse(Mp4VideoSize.set(file, 1080, 1920));
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void rejectsNonMp4() throws IOException {
        byte[] original = "not a video".getBytes(StandardCharsets.ISO_8859_1);
        File file = write(original);

        assertFalse(Mp4VideoSize.set(file, 1080, 1920));
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }

    private File write(byte[] bytes) throws IOException {
        File file = tmp.newFile("clip.mp4");
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** moov sits after mdat, as FFmpeg writes it. */
    private static byte[] mp4(byte[]... tracks) {
        return concat(box("ftyp", new byte[8]), box("mdat", new byte[32]),
                box("moov", concat(box("mvhd", new byte[100]), concat(tracks))));
    }

    private static byte[] track(String codec, int width, int height) {
        ByteBuffer tkhd = ByteBuffer.allocate(84);
        tkhd.putInt(76, width << 16).putInt(80, height << 16);

        // Sample entry payload: 24 bytes of fixed fields, then width and height
        ByteBuffer entry = ByteBuffer.allocate(70);
        entry.putShort(24, (short) width).putShort(26, (short) height);

        ByteBuffer stsdHeader = ByteBuffer.allocate(8);
        stsdHeader.putInt(4, 1);
        byte[] stsd = box("stsd", concat(stsdHeader.array(), box(codec, entry.array())));

        return box("trak", concat(box("tkhd", tkhd.array()),
                box("mdia", concat(box("mdhd", new byte[24]),
                        box("minf", box("stbl", stsd))))));
    }

    private static byte[] box(String type, byte[] payload) {
        return concat(ByteBuffer.allocate(4).putInt(8 + payload.length).array(),
                type.getBytes(StandardCharsets.ISO_8859_1), payload);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
