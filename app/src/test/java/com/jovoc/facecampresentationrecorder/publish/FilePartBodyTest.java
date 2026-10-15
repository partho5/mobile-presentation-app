package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import okio.Buffer;

/** The file-slice request body of a part PUT. */
public class FilePartBodyTest {

    private File file;
    private byte[] data;

    @Before
    public void setUp() throws IOException {
        // Not a multiple of the chunk size, with distinct bytes so a wrong offset shows.
        data = new byte[200_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + i / 251);
        file = File.createTempFile("part", ".bin");
        Files.write(file.toPath(), data);
    }

    @After
    public void tearDown() {
        file.delete();
    }

    private static byte[] write(FilePartBody body) throws IOException {
        Buffer sink = new Buffer();
        body.writeTo(sink);
        return sink.readByteArray();
    }

    @Test
    public void writesExactlyTheRangeAndDeclaresItsLength() throws IOException {
        FilePartBody body = new FilePartBody(file, 70_000, 100_000, null, null);
        assertEquals(100_000, body.contentLength());
        assertNull(body.contentType());
        assertArrayEquals(Arrays.copyOfRange(data, 70_000, 170_000), write(body));
    }

    @Test
    public void lastPartIsTheRemainder() throws IOException {
        long offset = 150_000;
        long length = data.length - offset;
        FilePartBody body = new FilePartBody(file, offset, length, null, null);
        assertEquals(50_000, body.contentLength());
        assertArrayEquals(Arrays.copyOfRange(data, 150_000, data.length), write(body));
    }

    @Test
    public void reportsEveryByteWritten() throws IOException {
        AtomicLong counted = new AtomicLong();
        write(new FilePartBody(file, 0, 123_456, counted::addAndGet, null));
        assertEquals(123_456, counted.get());
    }

    @Test
    public void stopsWhenTheFlagTurnsTrue() throws IOException {
        AtomicLong counted = new AtomicLong();
        FilePartBody body = new FilePartBody(file, 0, data.length, counted::addAndGet,
                () -> counted.get() >= 64 * 1024);
        try {
            write(body);
            fail("expected stop");
        } catch (FilePartBody.StoppedException expected) {
            assertEquals(64 * 1024, counted.get());
        }
    }

    @Test
    public void aFileShorterThanDeclaredFailsInsteadOfSendingShort() throws IOException {
        FilePartBody body = new FilePartBody(file, 150_000, 100_000, null, null);
        try {
            write(body);
            fail("expected short file");
        } catch (FilePartBody.ShortFileException expected) {
            // fine
        }
    }
}
