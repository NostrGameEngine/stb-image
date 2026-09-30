package org.ngengine.stbimage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.function.IntFunction;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PngBitDepthTest {
    @Test
    void load16PreservesLowBytesForEveryColorTypeAndAllocator() throws IOException {
        List<IntFunction<ByteBuffer>> allocators = Arrays.asList(ByteBuffer::allocate,
                size -> ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN));
        for (IntFunction<ByteBuffer> allocator : allocators) {
            for (int channels = 1; channels <= 4; channels++) {
                int[] samples = new int[4 * channels];
                for (int i = 0; i < samples.length; i++) samples[i] = (257 + i * 4369) & 65535;
                byte[] png = png(channels, 16, samples);
                for (boolean flip : new boolean[]{false, true}) {
                    StbDecoder decoder = new StbImage(allocator).getDecoder(ByteBuffer.wrap(png), flip);
                    StbImageResult result = decoder.load16(channels);
                    assertTrue(result.is16Bit());
                    assertEquals(channels, result.getChannels());
                    assertEquals(8 * channels, result.getData().remaining());
                    for (int i = 0; i < samples.length; i++) {
                        int rowSize = 2 * channels;
                        int source = flip ? (1 - i / rowSize) * rowSize + i % rowSize : i;
                        assertEquals(samples[source], Short.toUnsignedInt(result.getData().getShort(i * 2)));
                    }
                }
            }
        }
    }

    @Test
    void load16SupportsRequestedChannelConversionWithoutQuantization() throws IOException {
        int[] samples = {257, 32768, 65534, 4660, 258, 32769, 65535, 4661,
                259, 32770, 1, 4662, 260, 32771, 2, 4663};
        for (boolean littleEndian : new boolean[]{false, true}) {
            StbImage image = new StbImage(size -> ByteBuffer.allocateDirect(size)
                    .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN));
            StbDecoder decoder = image.getDecoder(ByteBuffer.wrap(png(4, 16, samples)), false);
            StbImageResult result = decoder.load16(3);
            assertEquals(3, result.getChannels());
            assertEquals(24, result.getData().remaining());
            for (int i = 0; i < 12; i++) {
                assertEquals(samples[(i / 3) * 4 + i % 3], Short.toUnsignedInt(result.getData().getShort(i * 2)));
            }
        }
    }

    @Test
    void loadStillReturnsEightBitPixelsAfterLoadingSixteenBitPixels() throws IOException {
        int[] samples = {257, 32768, 65535, 4660};
        StbDecoder decoder = new StbImage().getDecoder(ByteBuffer.wrap(png(1, 16, samples)), false);
        decoder.load16(1);
        StbImageResult result = decoder.load(1);
        assertFalse(result.is16Bit());
        assertEquals(4, result.getData().remaining());
        for (int i = 0; i < samples.length; i++) assertEquals(samples[i] >>> 8, result.getData().get(i) & 255);
    }

    @Test
    void eightBitUpconversionReturnsAReadableBuffer() throws IOException {
        int[] samples = {0, 1, 127, 255};
        StbDecoder decoder = new StbImage().getDecoder(ByteBuffer.wrap(png(1, 8, samples)), false);
        StbImageResult result = decoder.load16(1);
        assertTrue(result.is16Bit());
        assertEquals(8, result.getData().remaining());
        for (int i = 0; i < samples.length; i++) {
            assertEquals(samples[i] * 257, Short.toUnsignedInt(result.getData().getShort(i * 2)));
        }
    }

    private static byte[] png(int channels, int depth, int[] samples) throws IOException {
        int[] colorTypes = {0, 4, 2, 6};
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) {
            for (int row = 0; row < 2; row++) {
                deflate.write(0);
                for (int i = 0; i < 2 * channels; i++) {
                    int sample = samples[row * 2 * channels + i];
                    if (depth == 16) deflate.write(sample >>> 8);
                    deflate.write(sample & 255);
                }
            }
        }
        byte[] header = ByteBuffer.allocate(13).putInt(2).putInt(2)
                .put((byte) depth).put((byte) colorTypes[channels - 1]).put(new byte[3]).array();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(encoded)) {
            output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            String[] names = {"IHDR", "IDAT", "IEND"};
            byte[][] chunks = {header, compressed.toByteArray(), new byte[0]};
            for (int i = 0; i < names.length; i++) {
                byte[] type = names[i].getBytes(StandardCharsets.US_ASCII);
                output.writeInt(chunks[i].length);
                output.write(type);
                output.write(chunks[i]);
                CRC32 crc = new CRC32();
                crc.update(type);
                crc.update(chunks[i]);
                output.writeInt((int) crc.getValue());
            }
        }
        return encoded.toByteArray();
    }
}
