package com.jovoc.facecampresentationrecorder.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Rewrites the frame size an MP4 declares for its H.264 video track, in place.
 *
 * {@link VideoCropHelper} crops without re-encoding, by changing the crop rectangle
 * inside the H.264 stream. FFmpeg copies the container headers from the source, so
 * they still claim the uncropped size; Android's metadata reader and the gallery
 * trust those headers, not the stream. This brings the two back in line.
 */
public final class Mp4VideoSize {

    /** Bytes from the start of a video sample entry to its 16-bit width field. */
    private static final int SAMPLE_ENTRY_WIDTH_OFFSET = 32;

    private Mp4VideoSize() {}

    /** One box: where its payload starts and where the box ends. */
    private static final class Box {
        final long payload;
        final long end;

        Box(long payload, long end) {
            this.payload = payload;
            this.end = end;
        }
    }

    /**
     * Sets the declared size of every H.264 video track.
     *
     * @return true if a video track was found and updated
     */
    public static boolean set(File mp4, int width, int height) {
        try (RandomAccessFile raf = new RandomAccessFile(mp4, "rw")) {
            Box moov = findChild(raf, 0, raf.length(), "moov");
            if (moov == null) return false;

            boolean patched = false;
            long pos = moov.payload;
            while (pos < moov.end) {
                Box box = readBox(raf, pos, moov.end);
                if (box == null) break;
                if ("trak".equals(typeAt(raf, pos))) {
                    patched |= patchTrack(raf, box, width, height);
                }
                pos = box.end;
            }
            return patched;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean patchTrack(RandomAccessFile raf, Box trak, int width, int height)
            throws IOException {
        Box tkhd = findChild(raf, trak.payload, trak.end, "tkhd");
        Box stsd = findPath(raf, trak, "mdia", "minf", "stbl", "stsd");
        if (tkhd == null || stsd == null) return false;

        // stsd payload: version/flags (4), entry count (4), then the sample entries
        long entry = stsd.payload + 8;
        if (entry + SAMPLE_ENTRY_WIDTH_OFFSET + 4 > stsd.end) return false;
        String codec = typeAt(raf, entry);
        if (!"avc1".equals(codec) && !"avc3".equals(codec)) return false;

        raf.seek(entry + SAMPLE_ENTRY_WIDTH_OFFSET);
        raf.writeShort(width);
        raf.writeShort(height);

        // tkhd ends with the display width and height as 16.16 fixed point
        raf.seek(tkhd.end - 8);
        raf.writeInt(width << 16);
        raf.writeInt(height << 16);
        return true;
    }

    private static Box findPath(RandomAccessFile raf, Box parent, String... types)
            throws IOException {
        Box box = parent;
        for (String type : types) {
            box = findChild(raf, box.payload, box.end, type);
            if (box == null) return null;
        }
        return box;
    }

    private static Box findChild(RandomAccessFile raf, long start, long end, String type)
            throws IOException {
        long pos = start;
        while (pos < end) {
            Box box = readBox(raf, pos, end);
            if (box == null) return null;
            if (type.equals(typeAt(raf, pos))) return box;
            pos = box.end;
        }
        return null;
    }

    /** Reads the box header at {@code pos}; null if it does not fit inside its container. */
    private static Box readBox(RandomAccessFile raf, long pos, long containerEnd)
            throws IOException {
        if (pos + 8 > containerEnd) return null;
        raf.seek(pos);
        long size = raf.readInt() & 0xFFFFFFFFL;
        long header = 8;
        if (size == 1) {
            // 64-bit size follows the type
            if (pos + 16 > containerEnd) return null;
            raf.seek(pos + 8);
            size = raf.readLong();
            header = 16;
        } else if (size == 0) {
            // box runs to the end of its container
            size = containerEnd - pos;
        }
        if (size < header || pos + size > containerEnd) return null;
        return new Box(pos + header, pos + size);
    }

    private static String typeAt(RandomAccessFile raf, long boxStart) throws IOException {
        byte[] type = new byte[4];
        raf.seek(boxStart + 4);
        raf.readFully(type);
        return new String(type, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
