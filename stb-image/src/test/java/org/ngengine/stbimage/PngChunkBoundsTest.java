package org.ngengine.stbimage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PngChunkBoundsTest {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    @Test
    void rejectsOversizedAndNegativeIdatLengthsBeforeOutputAllocation() throws IOException {
        for (int length : new int[]{1, 1024, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            byte[] input = concat(SIGNATURE, chunk("IHDR", header(8)),
                    ByteBuffer.allocate(12).putInt(length).putInt(0x49444154).putInt(0).array());
            AtomicInteger allocations = new AtomicInteger();
            PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(input), size -> {
                allocations.incrementAndGet();
                return ByteBuffer.allocate(size);
            }, false);
            assertThrows(StbFailureException.class, () -> decoder.load(1), "length=" + length);
            assertThrows(StbFailureException.class, () -> decoder.load16(1), "length=" + length);
            assertEquals(0, allocations.get());
        }
    }

    @Test
    void metadataRejectsOversizedAndNegativeChunksBeforeIhdr() throws IOException {
        for (int length : new int[]{1, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            byte[] input = concat(SIGNATURE,
                    ByteBuffer.allocate(12).putInt(length).putInt(0x74455874).putInt(0).array());
            PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(input), ByteBuffer::allocate, false);
            assertNull(decoder.info(), "length=" + length);
            assertThrows(StbFailureException.class, () -> decoder.load(1));
        }
    }

    @Test
    void truncatedSignaturesHeadersPayloadsAndCrcsHaveConsistentFailure() throws IOException {
        byte[] ihdr = concat(SIGNATURE, chunk("IHDR", header(8)));
        for (int length = 0; length < ihdr.length; length++) {
            PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(Arrays.copyOf(ihdr, length)),
                    ByteBuffer::allocate, false);
            assertNull(decoder.info(), "prefix=" + length);
            assertThrows(StbFailureException.class, () -> decoder.load(1), "prefix=" + length);
        }
        byte[] complete = png(8, 0x7b);
        for (int missing = 1; missing <= 12; missing++) {
            PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(Arrays.copyOf(complete, complete.length - missing)),
                    ByteBuffer::allocate, false);
            assertThrows(StbFailureException.class, () -> decoder.load(1), "missing=" + missing);
        }
    }

    @Test
    void rejectsInvalidFixedSizeChunks() throws IOException {
        byte[] compressed = compressedSample(8, 0x7b);
        for (byte[] invalid : new byte[][]{chunk("tRNS", new byte[1]), chunk("tRNS", new byte[3])}) {
            byte[] input = concat(SIGNATURE, chunk("IHDR", header(8)), invalid,
                    chunk("IDAT", compressed), chunk("IEND", new byte[0]));
            PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(input), ByteBuffer::allocate, false);
            assertThrows(StbFailureException.class, () -> decoder.load(1));
        }
        byte[] rgbHeader = header(8);
        rgbHeader[9] = 2;
        for (int length : new int[]{0, 1, 5, 7}) {
            byte[] rgb = concat(SIGNATURE, chunk("IHDR", rgbHeader), chunk("tRNS", new byte[length]),
                    chunk("IDAT", compressed), chunk("IEND", new byte[0]));
            PngDecoder rgbDecoder = new PngDecoder(ByteBuffer.wrap(rgb), ByteBuffer::allocate, false);
            assertThrows(StbFailureException.class, () -> rgbDecoder.load(3));
        }
        byte[] input = concat(SIGNATURE, chunk("IHDR", header(8)), chunk("IDAT", compressed),
                chunk("IEND", new byte[1]));
        PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(input), ByteBuffer::allocate, false);
        assertThrows(StbFailureException.class, () -> decoder.load(1));
    }

    @Test
    void idatSizeChecksUseArithmeticWithoutAllocatingPayloads() {
        assertEquals(0, PngDecoder.checkedIdatSize(0, 0));
        assertEquals(17, PngDecoder.checkedIdatSize(8, 9));
        assertThrows(StbFailureException.class, () -> PngDecoder.checkedIdatSize(-1, 0));
        assertThrows(StbFailureException.class, () -> PngDecoder.checkedIdatSize(0, -1));
        assertThrows(StbFailureException.class, () -> PngDecoder.checkedIdatSize(Integer.MAX_VALUE, 1));
        assertThrows(StbFailureException.class, () -> PngDecoder.checkedIdatSize(128 * 1024 * 1024,
                128 * 1024 * 1024 + 1));
    }

    @Test
    void configuredSingleAndTotalLimitsRejectSmallMultiIdatInputs() throws Exception {
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Paths.get(PngLimitsProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Paths.get(PngDecoder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (String limit : new String[]{"single", "total"}) {
            Process process = new ProcessBuilder(javaExecutable, "-Xmx32m", "-XX:ActiveProcessorCount=1",
                    "-cp", classpath, PngLimitsProbe.class.getName(), limit).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            assertTrue(finished, "isolated limits check timed out");
            String output = readOutput(process.getInputStream());
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.endsWith("PASS " + limit + "\n"), output);
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[1024];
        int count;
        while ((count = input.read(block)) != -1) {
            if (count > 8192 - output.size()) {
                throw new IOException("Isolated limits check exceeded output bound");
            }
            output.write(block, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void validEightBitAndSixteenBitPngsWithSplitAndEmptyIdatStillDecodeExactly() throws IOException {
        for (int depth : new int[]{8, 16}) {
            int sample = depth == 8 ? 0x7b : 0x1234;
            PngDecoder decoder = new PngDecoder(ByteBuffer.wrap(png(depth, sample)), ByteBuffer::allocate, false);
            assertNotNull(decoder.info());
            StbImageResult eightBit = decoder.load(1);
            assertEquals(1, eightBit.getWidth());
            assertEquals(1, eightBit.getHeight());
            assertEquals(1, eightBit.getData().remaining());
            assertFalse(eightBit.is16Bit());
            assertEquals(depth == 8 ? sample : sample >>> 8, eightBit.getData().get(0) & 255);
            StbImageResult sixteenBit = decoder.load16(1);
            assertTrue(sixteenBit.is16Bit());
            assertEquals(2, sixteenBit.getData().remaining());
            assertEquals(depth == 8 ? sample * 257 : sample,
                    Short.toUnsignedInt(sixteenBit.getData().getShort(0)));
        }
    }

    private static byte[] header(int depth) {
        return ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte) depth).put(new byte[4]).array();
    }

    private static byte[] compressedSample(int depth, int sample) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(output)) {
            deflate.write(0);
            if (depth == 16) deflate.write(sample >>> 8);
            deflate.write(sample & 255);
        }
        return output.toByteArray();
    }

    private static byte[] png(int depth, int sample) throws IOException {
        byte[] compressed = compressedSample(depth, sample);
        int split = compressed.length / 2;
        return concat(SIGNATURE, chunk("IHDR", header(depth)), chunk("IDAT", new byte[0]),
                chunk("IDAT", Arrays.copyOf(compressed, split)),
                chunk("IDAT", Arrays.copyOfRange(compressed, split, compressed.length)),
                chunk("IEND", new byte[0]));
    }

    private static byte[] chunk(String name, byte[] data) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(encoded)) {
            output.writeInt(data.length);
            output.write(type);
            output.write(data);
            output.writeInt((int) crc.getValue());
        }
        return encoded.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part);
        return output.toByteArray();
    }
}
