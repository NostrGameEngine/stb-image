package com.stb.image;

import com.stb.image.allocator.StbAllocator;
import org.junit.jupiter.api.Test;

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
    void testDefaultAllocator() {
        StbAllocator alloc = StbImage.getAllocator();
        assertNotNull(alloc);

        ByteBuffer buf = alloc.allocate(100);
        assertNotNull(buf);
        assertEquals(100, buf.capacity());
    }

    @Test
    void testSetCustomAllocator() {
        StbAllocator customAlloc = size -> {
            if (size <= 0) throw new IllegalArgumentException();
            return ByteBuffer.allocateDirect(size);
        };

        StbImage.setAllocator(customAlloc);
        assertSame(customAlloc, StbImage.getAllocator());

        // Reset to default
        StbImage.setAllocator(StbAllocator.DEFAULT);
    }

    @Test
    void testFlipVertically() {
        StbImage.setFlipVertically(true);
        assertTrue(StbImage.isFlipVertically());

        StbImage.setFlipVertically(false);
        assertFalse(StbImage.isFlipVertically());
    }

    @Test
    void testReadHelpers() {
        // Test readU8
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { (byte)0xFF, 0x00, 0x7F });
        assertEquals(255, StbImage.readU8(buf));
        assertEquals(0, StbImage.readU8(buf));
        assertEquals(127, StbImage.readU8(buf));
    }

    @Test
    void testReadString() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { 'H', 'e', 'l', 'l', 'o', 0, 'W', 'o', 'r', 'l', 'd' });
        assertEquals("Hello", StbImage.readString(buf, 10));
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
        assertDoesNotThrow(() -> StbImage.verticalFlip(src, 2, 2, 1, false));
    }

    @Test
    void testConvertChannelsNoChange() {
        // 3 channels to 3 channels - should return same data
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6 });

        ByteBuffer result = StbImage.convertChannels(src, 3, 2, 1, 3, false);

        assertNotNull(result);
    }

    @Test
    void testInfoWithInvalidData() {
        // Empty buffer should return null
        ByteBuffer empty = ByteBuffer.wrap(new byte[0]);
        assertNull(StbImage.info(empty));

        // Random data should return null (not a known format)
        ByteBuffer random = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        assertNull(StbImage.info(random));
    }

    @Test
    void testIsHdrWithInvalidData() {
        // Not HDR signature
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 });
        assertFalse(StbImage.isHdr(buf));

        // Too small
        ByteBuffer small = ByteBuffer.wrap(new byte[] { 1 });
        assertFalse(StbImage.isHdr(small));
    }

    @Test
    void testLoadWithInvalidData() {
        // Unknown format should throw
        byte[] invalid = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };

        assertThrows(StbFailureException.class, () -> {
            StbImage.load(invalid, 0);
        });
    }

    @Test
    void testLoad16() {
        // Should just call load for now
        byte[] data = new byte[] { 1, 2 };
        assertThrows(StbFailureException.class, () -> StbImage.load16(data, 0));
    }

    @Test
    void testLoadf() {
        // HDR not implemented
        byte[] data = new byte[] { 1, 2 };
        assertThrows(StbFailureException.class, () -> StbImage.loadf(data, 0));
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
    void testStbFailureException() {
        StbFailureException ex = new StbFailureException("test error");

        assertTrue(ex.getMessage().contains("test error"));
        assertEquals("test error", ex.getReason());
        assertFalse(ex.isUserMessage());

        StbFailureException exUser = new StbFailureException("user msg", true);
        assertTrue(exUser.isUserMessage());
    }
}
