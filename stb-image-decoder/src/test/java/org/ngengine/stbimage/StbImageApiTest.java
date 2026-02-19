package org.ngengine.stbimage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StbImage decoder API.
 */
public class StbImageApiTest {

    @Test
    void testConstants() {
        assertEquals(0, StbImage.STBI_DEFAULT);
        assertEquals(1, StbImage.STBI_GREY);
        assertEquals(2, StbImage.STBI_GREY_ALPHA);
        assertEquals(3, StbImage.STBI_RGB);
        assertEquals(4, StbImage.STBI_RGB_ALPHA);
    }

    @Test
    void testMaxDimensions() {
        assertEquals(1 << 24, StbImage.STBI_MAX_DIMENSIONS);
    }

    @Test
    void testIphonePngTogglesDefaultOff() {
        StbImage stb = new StbImage();
        assertFalse(stb.isConvertIphonePngToRgb());
        assertFalse(stb.isUnpremultiplyOnLoad());
        assertFalse(stb.isFillGifFirstFrameBackground());
    }

    @Test
    void testIphonePngTogglesCanBeConfigured() {
        StbImage stb = new StbImage();
        stb.setConvertIphonePngToRgb(true);
        stb.setUnpremultiplyOnLoad(true);
        stb.setFillGifFirstFrameBackground(true);
        assertTrue(stb.isConvertIphonePngToRgb());
        assertTrue(stb.isUnpremultiplyOnLoad());
        assertTrue(stb.isFillGifFirstFrameBackground());
    }

    @Test
    void testIphonePngTogglesAreWiredToPngDecoder() throws Exception {
        StbImage stb = new StbImage();
        stb.setConvertIphonePngToRgb(true);
        stb.setUnpremultiplyOnLoad(true);

        ByteBuffer pngSigOnly = ByteBuffer.wrap(new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0
        });

        StbDecoder decoder = stb.getDecoder(pngSigOnly, false);
        assertTrue(decoder instanceof PngDecoder);

        Field convert = PngDecoder.class.getDeclaredField("convertIphonePngToRgb");
        convert.setAccessible(true);
        Field unprem = PngDecoder.class.getDeclaredField("unpremultiplyOnLoad");
        unprem.setAccessible(true);

        assertTrue(convert.getBoolean(decoder));
        assertTrue(unprem.getBoolean(decoder));
    }

    @Test
    void testPngDeIphoneUnpremultiplyMatchesStbFormula() throws Exception {
        PngDecoder decoder = new PngDecoder(ByteBuffer.allocate(0), ByteBuffer::allocate, false);
        decoder.setUnpremultiplyOnLoad(true);
        Method deIphone = PngDecoder.class.getDeclaredMethod(
                "deIphone", ByteBuffer.class, int.class, int.class, int.class, boolean.class);
        deIphone.setAccessible(true);

        ByteBuffer rgba = ByteBuffer.wrap(new byte[] {
                64, 32, (byte) 128, (byte) 128
        });
        deIphone.invoke(decoder, rgba, 4, 1, 1, false);

        // stb formula: r=(b*255+half)/a, g=(g*255+half)/a, b=(old_r*255+half)/a
        assertEquals((byte) 255, rgba.get(0));
        assertEquals((byte) 64, rgba.get(1));
        assertEquals((byte) 128, rgba.get(2));
        assertEquals((byte) 128, rgba.get(3));
    }

    @Test
    void testGifBackgroundToggleIsWiredToGifDecoder() throws Exception {
        StbImage stb = new StbImage();
        stb.setFillGifFirstFrameBackground(true);
        byte[] gifData = loadResourceBytes("testData/image/single.gif");
        StbDecoder decoder = stb.getDecoder(ByteBuffer.wrap(gifData), false);
        assertTrue(decoder instanceof GifDecoder);

        Field field = GifDecoder.class.getDeclaredField("fillFirstFrameBackground");
        field.setAccessible(true);
        assertTrue(field.getBoolean(decoder));
    }

 
  
    
    

    @Test
    void testValidateDimensions() {
        // Valid dimensions should not throw
        assertDoesNotThrow(() -> StbImage.validateDimensions(1, 1));
        assertDoesNotThrow(() -> StbImage.validateDimensions(100, 100));
        assertDoesNotThrow(() -> StbImage.validateDimensions(16384, 16384));

        // Invalid should throw
        assertThrows(StbFailureException.class, () -> StbImage.validateDimensions(0, 100));
        assertThrows(StbFailureException.class, () -> StbImage.validateDimensions(100, 0));
        assertThrows(StbFailureException.class, () -> StbImage.validateDimensions(-1, 100));

        // Too large should throw
        assertThrows(StbFailureException.class, () ->
            StbImage.validateDimensions(1 << 25, 1));
    }

    @Test
    void testVerticalFlip() {
        // Test that the method exists and doesn't throw with valid input
        // The actual flip logic is tested via integration tests with real images
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put(new byte[] { 1, 2, 3, 4 });
        src.flip();

        // Should not throw
        assertDoesNotThrow(() -> StbImage.verticalFlip(ByteBuffer::allocate, src, 2, 2, 1, false));
    }

    @Test
    void testConvertChannelsNoChange() {
        // 3 channels to 3 channels - should return same data
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6 });

        ByteBuffer result = StbImage.convertChannels(ByteBuffer::allocate, src, 3, 2, 1, 3, false);

        assertNotNull(result);
    }

    @Test
    void testConvertChannelsMatrix8BitMatchesStbRules() {
        int width = 2;
        int height = 1;
        for (int src = 1; src <= 4; src++) {
            for (int dst = 1; dst <= 4; dst++) {
                byte[] input = new byte[width * height * src];
                for (int i = 0; i < input.length; i++) {
                    input[i] = (byte) (10 + i * 17);
                }

                ByteBuffer out = StbImage.convertChannels(ByteBuffer::allocate, ByteBuffer.wrap(input), src, width, height, dst, false);
                byte[] actual = new byte[width * height * dst];
                out.get(actual);
                byte[] expected = expectedConvert8(input, src, dst, width * height);
                assertArrayEquals(expected, actual, "8-bit convert mismatch src=" + src + " dst=" + dst);
            }
        }
    }

    @Test
    void testConvertChannelsMatrix16BitMatchesStbRules() {
        int width = 2;
        int height = 1;
        for (int src = 1; src <= 4; src++) {
            for (int dst = 1; dst <= 4; dst++) {
                int pixels = width * height;
                short[] inputShorts = new short[pixels * src];
                for (int i = 0; i < inputShorts.length; i++) {
                    inputShorts[i] = (short) (1000 + i * 1234);
                }
                ByteBuffer in = ByteBuffer.allocate(inputShorts.length * 2);
                for (short v : inputShorts) {
                    in.putShort(v);
                }
                in.flip();

                ByteBuffer out = StbImage.convertChannels(ByteBuffer::allocate, in, src, width, height, dst, true);
                short[] actual = new short[pixels * dst];
                for (int i = 0; i < actual.length; i++) {
                    actual[i] = out.getShort(i * 2);
                }
                short[] expected = expectedConvert16(inputShorts, src, dst, pixels);
                assertArrayEquals(expected, actual, "16-bit convert mismatch src=" + src + " dst=" + dst);
            }
        }
    }

    @Test
    void testVerticalFlipFloatChannels() {
        ByteBuffer src = ByteBuffer.allocate(2 * 2 * 3 * 4);
        float[] vals = new float[] {
            1, 2, 3, 4, 5, 6,
            7, 8, 9, 10, 11, 12
        };
        for (float v : vals) {
            src.putFloat(v);
        }
        src.flip();
        ByteBuffer flipped = StbImage.verticalFlip(ByteBuffer::allocate, src, 2, 2, 3, 4);
        float[] actual = new float[12];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = flipped.getFloat(i * 4);
        }
        assertArrayEquals(new float[] {7, 8, 9, 10, 11, 12, 1, 2, 3, 4, 5, 6}, actual);
    }

    @Test
    void testInfoWithInvalidData() {
        // Empty buffer should return null
        ByteBuffer empty = ByteBuffer.wrap(new byte[0]);
        StbImage stb = new StbImage();
        
        assertThrows(StbFailureException.class,()->{
            stb.getDecoder(empty, false).info();
        });

        // Random data should return null (not a known format)
        ByteBuffer random = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        assertThrows(StbFailureException.class,()->{
            stb.getDecoder(random, false).info();
        });
    }

    @Test
    void testIsHdrWithInvalidData() {
        // Not HDR signature
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 });
        assertFalse(HdrDecoder.isHdr(buf));

        // Too small
        ByteBuffer small = ByteBuffer.wrap(new byte[] { 1 });
        assertFalse(HdrDecoder.isHdr(small));
    }

    @Test
    void testLoadWithInvalidData() {
        // Unknown format should throw
        byte[] invalid = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };

        assertThrows(StbFailureException.class, () -> {
            StbImage stb = new StbImage();
            stb.getDecoder(ByteBuffer.wrap(invalid), false).load(0);
        });
    }

    @Test
    void testLoad16() {
        // Should just call load for now
        byte[] data = new byte[] { 1, 2 };
        assertThrows(StbFailureException.class, () -> {
            StbImage stb = new StbImage();
            stb.getDecoder(ByteBuffer.wrap(data), false).load16(0);
        });
    }

    @Test
    void testLoadf() {
        assertThrows(StbFailureException.class, () -> {
            StbImage stb = new StbImage();
            StbDecoder decoder = stb.getDecoder(ByteBuffer.wrap(loadResourceBytes("testData/image/single.gif")), false);
            decoder.loadf(0);
        });
    }

    @Test
    void testLoadfHdr() throws IOException {
        StbImage stb = new StbImage();
        StbDecoder decoder = stb.getDecoder(ByteBuffer.wrap(loadResourceBytes("testData/image/rgbe_rle.hdr")), false);
        StbImageResult result = decoder.loadf(3);
        assertTrue(result.isHdr());
        assertEquals(3, result.getChannels());
    }

    @Test
    void testStbImageResult() {
        ByteBuffer data = ByteBuffer.wrap(new byte[100]);
        StbImageResult result = new StbImageResult(data, 10, 10, 3, 4, false, false);

        assertEquals(10, result.getWidth());
        assertEquals(10, result.getHeight());
        assertEquals(3, result.getChannels());
        assertEquals(4, result.getRequestedChannels());
        assertFalse(result.is16Bit());
        assertFalse(result.isHdr());
        assertEquals(1, result.getBytesPerChannel());
        assertEquals(300, result.getDataSize());
        assertSame(data, result.getData());
    }

    @Test
    void testStbImageResult16Bit() {
        ByteBuffer data = ByteBuffer.wrap(new byte[200]);
        StbImageResult result = new StbImageResult(data, 10, 5, 2, 2, true, false);

        assertTrue(result.is16Bit());
        assertEquals(2, result.getBytesPerChannel());
        assertEquals(200, result.getDataSize());
    }

    @Test
    void testStbImageResultHdr() {
        ByteBuffer data = ByteBuffer.wrap(new byte[400]);
        StbImageResult result = new StbImageResult(data, 10, 5, 3, 3, false, true);

        assertTrue(result.isHdr());
        assertEquals(4, result.getBytesPerChannel());
        assertEquals(600, result.getDataSize());
    }

    @Test
    void testStbImageInfo() {
        StbImageInfo info = new StbImageInfo(100, 200, 3, false, StbImageInfo.ImageFormat.JPEG);

        assertEquals(100, info.getWidth());
        assertEquals(200, info.getHeight());
        assertEquals(3, info.getChannels());
        assertFalse(info.is16Bit());
        assertEquals(StbImageInfo.ImageFormat.JPEG, info.getFormat());
    }

    @Test
    void testPicDecoderFixtureUncompressedRgb() throws IOException {
        byte[] pic = loadResourceBytes("testData/image/minimal.pic");
        StbImage stb = new StbImage();
        StbDecoder decoder = stb.getDecoder(ByteBuffer.wrap(pic), false);
        assertTrue(decoder instanceof PicDecoder);

        StbImageInfo info = decoder.info();
        assertEquals(1, info.getWidth());
        assertEquals(1, info.getHeight());
        assertEquals(3, info.getChannels());
        assertEquals(StbImageInfo.ImageFormat.PIC, info.getFormat());

        StbImageResult result = decoder.load(0);
        assertEquals(3, result.getChannels());
        assertEquals(1, result.getWidth());
        assertEquals(1, result.getHeight());
        ByteBuffer data = result.getData();
        assertEquals(10, Byte.toUnsignedInt(data.get(0)));
        assertEquals(20, Byte.toUnsignedInt(data.get(1)));
        assertEquals(30, Byte.toUnsignedInt(data.get(2)));
    }

    @Test
    void testStbFailureException() {
        StbFailureException ex = new StbFailureException("test error");

        assertTrue(ex.getMessage().contains("test error"));
        assertEquals("test error", ex.getReason());
        assertFalse(ex.isUserMessage());

        StbFailureException exUser = new StbFailureException("user msg", true);
        assertTrue(exUser.isUserMessage());
    }

    private static byte[] loadResourceBytes(String path) throws IOException {
        try (InputStream in = StbImageApiTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "Missing resource: " + path);
            return in.readAllBytes();
        }
    }

    private static byte[] expectedConvert8(byte[] input, int srcChannels, int dstChannels, int pixels) {
        byte[] out = new byte[pixels * dstChannels];
        for (int p = 0; p < pixels; p++) {
            int s = p * srcChannels;
            int d = p * dstChannels;
            int c0 = Byte.toUnsignedInt(input[s]);
            int c1 = srcChannels > 1 ? Byte.toUnsignedInt(input[s + 1]) : 0;
            int c2 = srcChannels > 2 ? Byte.toUnsignedInt(input[s + 2]) : 0;
            int c3 = srcChannels > 3 ? Byte.toUnsignedInt(input[s + 3]) : 0;
            int y = ((c0 * 77) + (c1 * 150) + (29 * c2)) >> 8;

            switch ((srcChannels << 3) + dstChannels) {
                case (1 << 3) + 2:
                    out[d] = (byte) c0; out[d + 1] = (byte) 255; break;
                case (1 << 3) + 3:
                    out[d] = (byte) c0; out[d + 1] = (byte) c0; out[d + 2] = (byte) c0; break;
                case (1 << 3) + 4:
                    out[d] = (byte) c0; out[d + 1] = (byte) c0; out[d + 2] = (byte) c0; out[d + 3] = (byte) 255; break;
                case (2 << 3) + 1:
                    out[d] = (byte) c0; break;
                case (2 << 3) + 3:
                    out[d] = (byte) c0; out[d + 1] = (byte) c0; out[d + 2] = (byte) c0; break;
                case (2 << 3) + 4:
                    out[d] = (byte) c0; out[d + 1] = (byte) c0; out[d + 2] = (byte) c0; out[d + 3] = (byte) c1; break;
                case (3 << 3) + 4:
                    out[d] = (byte) c0; out[d + 1] = (byte) c1; out[d + 2] = (byte) c2; out[d + 3] = (byte) 255; break;
                case (3 << 3) + 1:
                    out[d] = (byte) y; break;
                case (3 << 3) + 2:
                    out[d] = (byte) y; out[d + 1] = (byte) 255; break;
                case (4 << 3) + 1:
                    out[d] = (byte) y; break;
                case (4 << 3) + 2:
                    out[d] = (byte) y; out[d + 1] = (byte) c3; break;
                case (4 << 3) + 3:
                    out[d] = (byte) c0; out[d + 1] = (byte) c1; out[d + 2] = (byte) c2; break;
                case (1 << 3) + 1:
                case (2 << 3) + 2:
                case (3 << 3) + 3:
                case (4 << 3) + 4:
                    System.arraycopy(input, s, out, d, dstChannels);
                    break;
                default:
                    throw new IllegalStateException("bad combo");
            }
        }
        return out;
    }

    private static short[] expectedConvert16(short[] input, int srcChannels, int dstChannels, int pixels) {
        short[] out = new short[pixels * dstChannels];
        for (int p = 0; p < pixels; p++) {
            int s = p * srcChannels;
            int d = p * dstChannels;
            int c0 = Short.toUnsignedInt(input[s]);
            int c1 = srcChannels > 1 ? Short.toUnsignedInt(input[s + 1]) : 0;
            int c2 = srcChannels > 2 ? Short.toUnsignedInt(input[s + 2]) : 0;
            int c3 = srcChannels > 3 ? Short.toUnsignedInt(input[s + 3]) : 0;
            int y = ((c0 * 77) + (c1 * 150) + (29 * c2)) >> 8;

            switch ((srcChannels << 3) + dstChannels) {
                case (1 << 3) + 2:
                    out[d] = (short) c0; out[d + 1] = (short) 0xFFFF; break;
                case (1 << 3) + 3:
                    out[d] = (short) c0; out[d + 1] = (short) c0; out[d + 2] = (short) c0; break;
                case (1 << 3) + 4:
                    out[d] = (short) c0; out[d + 1] = (short) c0; out[d + 2] = (short) c0; out[d + 3] = (short) 0xFFFF; break;
                case (2 << 3) + 1:
                    out[d] = (short) c0; break;
                case (2 << 3) + 3:
                    out[d] = (short) c0; out[d + 1] = (short) c0; out[d + 2] = (short) c0; break;
                case (2 << 3) + 4:
                    out[d] = (short) c0; out[d + 1] = (short) c0; out[d + 2] = (short) c0; out[d + 3] = (short) c1; break;
                case (3 << 3) + 4:
                    out[d] = (short) c0; out[d + 1] = (short) c1; out[d + 2] = (short) c2; out[d + 3] = (short) 0xFFFF; break;
                case (3 << 3) + 1:
                    out[d] = (short) y; break;
                case (3 << 3) + 2:
                    out[d] = (short) y; out[d + 1] = (short) 0xFFFF; break;
                case (4 << 3) + 1:
                    out[d] = (short) y; break;
                case (4 << 3) + 2:
                    out[d] = (short) y; out[d + 1] = (short) c3; break;
                case (4 << 3) + 3:
                    out[d] = (short) c0; out[d + 1] = (short) c1; out[d + 2] = (short) c2; break;
                case (1 << 3) + 1:
                case (2 << 3) + 2:
                case (3 << 3) + 3:
                case (4 << 3) + 4:
                    System.arraycopy(input, s, out, d, dstChannels);
                    break;
                default:
                    throw new IllegalStateException("bad combo");
            }
        }
        return out;
    }
}
