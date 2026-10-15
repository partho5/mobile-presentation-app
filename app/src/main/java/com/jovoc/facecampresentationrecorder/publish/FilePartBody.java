package com.jovoc.facecampresentationrecorder.publish;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * The raw bytes of one range of a file as a request body, for a part PUT to R2. Its
 * {@link #contentLength()} is the exact range length (the URL is signed for it; chunked transfer
 * is wrong), and it reads in small buffers, so a 16 MiB part is never held in memory.
 */
final class FilePartBody extends RequestBody {

    /** The stop flag turned true while the part was being written. */
    static final class StoppedException extends IOException {
        StoppedException() {
            super("stopped");
        }
    }

    /** The file ended before the declared range did (it changed under the upload). */
    static final class ShortFileException extends IOException {
        ShortFileException() {
            super("file shorter than declared");
        }
    }

    private static final int CHUNK = 64 * 1024;

    private final File file;
    private final long offset;
    private final long length;
    private final LongConsumer onBytes;
    private final BooleanSupplier stopped;

    /**
     * @param onBytes called with the bytes of every chunk written (may be null)
     * @param stopped checked before every chunk; true aborts with {@link StoppedException}
     */
    FilePartBody(File file, long offset, long length, LongConsumer onBytes, BooleanSupplier stopped) {
        this.file = file;
        this.offset = offset;
        this.length = length;
        this.onBytes = onBytes;
        this.stopped = stopped;
    }

    @Override
    public MediaType contentType() {
        return null;
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[CHUNK];
            long left = length;
            while (left > 0) {
                if (stopped != null && stopped.getAsBoolean()) throw new StoppedException();
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new ShortFileException();
                sink.write(buf, 0, n);
                left -= n;
                if (onBytes != null) onBytes.accept(n);
            }
        }
    }
}
