package com.stb.image;

import com.stb.image.allocator.StbAllocator;
import java.nio.ByteBuffer;

/**
 * Pure Java implementation of stb_image.h
 * Compatible with Java 11, designed for jMonkeyEngine and TeaVM.
 */
public class StbImage {

    public static final int STBI_DEFAULT = 0;
    public static final int STBI_GREY = 1;
    public static final int STBI_GREY_ALPHA = 2;
    public static final int STBI_RGB = 3;
    public static final int STBI_RGB_ALPHA = 4;

    public static final int STBI_MAX_DIMENSIONS = 1 << 24;

    private static StbAllocator allocator = StbAllocator.DEFAULT;
    private static boolean flipVertically = false;

    public static void setAllocator(StbAllocator allocator) {
        StbImage.allocator = (allocator != null) ? allocator : StbAllocator.DEFAULT;
    }

    public static StbAllocator getAllocator() {
        return allocator;
    }

    public static void setFlipVertically(boolean flip) {
        flipVertically = flip;
    }

    public static boolean isFlipVertically() {
        return flipVertically;
    }

    public static StbImageResult load(byte[] buffer, int desiredChannels) {
        return load(ByteBuffer.wrap(buffer), desiredChannels);
    }

    public static StbImageResult load(ByteBuffer buffer, int desiredChannels) {
        // Detect format and delegate to appropriate decoder
        StbImageInfo imageInfo = info(buffer);
        if (imageInfo == null) {
            throw new StbFailureException("Unknown or invalid image format");
        }

        // Rewind buffer before decoding (info() may have modified position)
        buffer.rewind();

        switch (imageInfo.getFormat()) {
            case PNG:
                return PngDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case JPEG:
                return JpegDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case BMP:
                return BmpDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case GIF:
                return GifDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case TGA:
                return TgaDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case HDR:
                return HdrDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case PSD:
                return PsdDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            case PNM:
                return PnmDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
            default:
                throw new StbFailureException("Unsupported image format");
        }
    }

    public static StbImageResult load16(byte[] buffer, int desiredChannels) {
        return load16(ByteBuffer.wrap(buffer), desiredChannels);
    }

    public static StbImageResult load16(ByteBuffer buffer, int desiredChannels) {
        // Load and convert to 16-bit if needed
        StbImageInfo imageInfo = info(buffer);
        if (imageInfo == null) {
            throw new StbFailureException("Unknown or invalid image format");
        }

        StbImageResult result = load(buffer, desiredChannels);
        // If already 16-bit, return as-is, otherwise convert
        if (result.is16Bit()) {
            return result;
        }

        // Convert 8-bit to 16-bit
        ByteBuffer data8 = result.getData();
        int channels = result.getChannels();
        ByteBuffer data16 = allocator.allocate(result.getWidth() * result.getHeight() * channels * 2);

        for (int i = 0; i < data8.remaining(); i++) {
            int val = Byte.toUnsignedInt(data8.get(i));
            data16.putShort(i * 2, (short) (val | (val << 8)));
        }

        data16.flip();
        return new StbImageResult(data16, result.getWidth(), result.getHeight(), channels, desiredChannels, true, false);
    }

    public static StbImageResult loadf(byte[] buffer, int desiredChannels) {
        return loadf(ByteBuffer.wrap(buffer), desiredChannels);
    }

    public static StbImageResult loadf(ByteBuffer buffer, int desiredChannels) {
        // HDR files
        StbImageInfo imageInfo = info(buffer);
        if (imageInfo == null || imageInfo.getFormat() != StbImageInfo.ImageFormat.HDR) {
            throw new StbFailureException("Not an HDR image");
        }

        return HdrDecoder.decode(buffer, desiredChannels, allocator, flipVertically);
    }

    public static StbImageInfo info(ByteBuffer buffer) {
        return info(buffer, allocator);
    }

    public static StbImageInfo info(ByteBuffer buffer, StbAllocator alloc) {
        if (buffer == null || !buffer.hasRemaining()) {
            return null;
        }

        // Ensure buffer has enough data to detect format
        if (buffer.remaining() < 12) {
            return null;
        }

        // Check PNG signature
        if (isPng(buffer)) {
            buffer.rewind();
            return PngDecoder.getInfo(buffer, alloc);
        }

        // Check JPEG SOI marker
        buffer.rewind();
        if (isJpeg(buffer)) {
            buffer.rewind();
            return JpegDecoder.getInfo(buffer, alloc);
        }

        // Check BMP signature
        buffer.rewind();
        if (isBmp(buffer)) {
            buffer.rewind();
            return BmpDecoder.getInfo(buffer, alloc);
        }

        // Check GIF signature
        buffer.rewind();
        if (isGif(buffer)) {
            buffer.rewind();
            return GifDecoder.getInfo(buffer, alloc);
        }

        // Check PNM signature before TGA (TGA is catch-all)
        buffer.rewind();
        if (isPnm(buffer)) {
            buffer.rewind();
            return PnmDecoder.getInfo(buffer, alloc);
        }

        // Check PSD signature before TGA (TGA is catch-all that returns true)
        buffer.rewind();
        if (isPsd(buffer)) {
            buffer.rewind();
            return PsdDecoder.getInfo(buffer, alloc);
        }

        // Check TGA signature (TGA has no specific signature)
        buffer.rewind();
        if (isTga(buffer)) {
            buffer.rewind();
            return TgaDecoder.getInfo(buffer, alloc);
        }

        // Check HDR signature
        buffer.rewind();
        if (isHdr(buffer)) {
            buffer.rewind();
            return HdrDecoder.getInfo(buffer, alloc);
        }

        return null;
    }

    public static StbImageInfo info(byte[] buffer) {
        return info(ByteBuffer.wrap(buffer));
    }

    public static boolean isHdr(ByteBuffer buffer) {
        return isHdrFormat(buffer.duplicate());
    }

    public static boolean isHdr(ByteBuffer buffer, StbAllocator alloc) {
        return isHdrFormat(buffer);
    }

    private static boolean isHdrFormat(ByteBuffer buffer) {
        if (buffer.remaining() < 11) return false;
        buffer.mark();
        try {
            // Skip whitespace
            while (buffer.hasRemaining()) {
                char c = (char) (buffer.get() & 0xFF);
                if (!Character.isWhitespace(c)) {
                    buffer.position(buffer.position() - 1);
                    break;
                }
            }
            // Check for #?RADIANCE or #?RGBE
            byte[] sig = new byte[10];
            buffer.get(sig);
            return (sig[0] == '#' && sig[1] == '?') &&
                   ((sig[2] == 'R' && sig[3] == 'A' && sig[4] == 'D' && sig[5] == 'I') ||
                    (sig[2] == 'R' && sig[3] == 'G' && sig[4] == 'B' && sig[5] == 'E'));
        } finally {
            buffer.reset();
        }
    }

    public static boolean is16Bit(ByteBuffer buffer) {
        StbImageInfo info = info(buffer);
        return info != null && info.is16Bit();
    }

    public static boolean is16Bit(ByteBuffer buffer, StbAllocator alloc) {
        StbImageInfo info = info(buffer, alloc);
        return info != null && info.is16Bit();
    }

    // Helper methods to detect image formats
    private static boolean isPng(ByteBuffer buffer) {
        if (buffer.remaining() < 8) return false;
        buffer.mark();
        byte[] sig = new byte[8];
        buffer.get(sig);
        buffer.reset();
        return sig[0] == (byte) 0x89 && sig[1] == 0x50 && sig[2] == 0x4E && sig[3] == 0x47
            && sig[4] == 0x0D && sig[5] == 0x0A && sig[6] == 0x1A && sig[7] == 0x0A;
    }

    private static boolean isJpeg(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return false;
        buffer.mark();
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        buffer.reset();
        return b0 == 0xFF && b1 == 0xD8;
    }

    private static boolean isBmp(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return false;
        buffer.mark();
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        buffer.reset();
        return b0 == 'B' && b1 == 'M';
    }

    private static boolean isGif(ByteBuffer buffer) {
        if (buffer.remaining() < 6) return false;
        buffer.mark();
        byte[] sig = new byte[6];
        buffer.get(sig);
        buffer.reset();
        return (sig[0] == 'G' && sig[1] == 'I' && sig[2] == 'F' && sig[3] == '8' && (sig[4] == '7' || sig[4] == '9') && sig[5] == 'a');
    }

    private static boolean isTga(ByteBuffer buffer) {
        // TGA has no specific signature, return true if other formats don't match
        // (but check PSD first to avoid misdetection)
        if (buffer.remaining() < 3) return false;
        return true;
    }

    private static boolean isPsd(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;
        buffer.mark();
        int sig = buffer.getInt();
        int version = buffer.getShort() & 0xFFFF;
        buffer.reset();
        return sig == 0x38425053 && (version == 1 || version == 2);
    }

    private static boolean isPnm(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return false;
        buffer.mark();
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        buffer.reset();
        return b0 == 'P' && (b1 >= '1' && b1 <= '9') && b1 != '8' && b1 != '9';
    }

    // Helper methods
    public static String readString(ByteBuffer buffer, int maxLength) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLength && buffer.hasRemaining(); i++) {
            char c = (char) (buffer.get() & 0xFF);
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }

    public static int readU8(ByteBuffer buffer) {
        return buffer.get() & 0xFF;
    }

    public static int readS8(ByteBuffer buffer) {
        return buffer.get();
    }

    public static int readU16LE(ByteBuffer buffer) {
        return Short.toUnsignedInt(buffer.getShort());
    }

    public static int readU16BE(ByteBuffer buffer) {
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        return (b0 << 8) | b1;
    }

    public static int readU32LE(ByteBuffer buffer) {
        return buffer.getInt();
    }

    public static int readU32BE(ByteBuffer buffer) {
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        int b2 = buffer.get() & 0xFF;
        int b3 = buffer.get() & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    public static int readS32LE(ByteBuffer buffer) {
        return buffer.getInt();
    }

    public static float readF32LE(ByteBuffer buffer) {
        return buffer.getFloat();
    }

    public static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new StbFailureException("Invalid image dimensions: " + width + "x" + height);
        }
        if (width > STBI_MAX_DIMENSIONS || height > STBI_MAX_DIMENSIONS) {
            throw new StbFailureException("Image dimensions exceed maximum");
        }
        long totalPixels = (long) width * height;
        if (totalPixels > 0x7FFFFFFF) {
            throw new StbFailureException("Image too large");
        }
    }

    public static ByteBuffer convertChannels(ByteBuffer src, int srcChannels, int width, int height, int desiredChannels, boolean is16Bit) {
        int bytesPerChannel = is16Bit ? 2 : 1;
        int srcStride = width * srcChannels * bytesPerChannel;
        int dstStride = width * desiredChannels * bytesPerChannel;
        int srcSize = width * height * srcChannels * bytesPerChannel;
        int dstSize = width * height * desiredChannels * bytesPerChannel;

        // Always create a new buffer to ensure consistent behavior
        // First copy all source data to ensure we can read it
        src.rewind();
        ByteBuffer srcCopy = allocator.allocate(src.capacity());
        srcCopy.put(src);
        srcCopy.rewind();

        // If same channels, just return the copy with proper limit
        if (srcChannels == desiredChannels) {
            srcCopy.rewind();
            return srcCopy;
        }

        // Different channel counts - perform conversion
        ByteBuffer dst = allocator.allocate(dstSize);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcPixelStart = (y * width + x) * srcChannels * bytesPerChannel;
                int dstPixelStart = (y * width + x) * desiredChannels * bytesPerChannel;

                for (int c = 0; c < desiredChannels; c++) {
                    int dstOffset = dstPixelStart + c * bytesPerChannel;

                    // Handle alpha channel - set to max value
                    if (c == desiredChannels - 1 && desiredChannels == 4 && srcChannels < 4) {
                        // Alpha channel - set to max
                        if (is16Bit) {
                            dst.putShort(dstOffset, (short) 65535);
                        } else {
                            dst.put(dstOffset, (byte) 255);
                        }
                        continue;
                    }

                    // Get source channel index (clamp to valid range for RGB channels)
                    int srcChan = Math.min(c, srcChannels - 1);
                    int srcOffset = srcPixelStart + srcChan * bytesPerChannel;

                    if (is16Bit) {
                        short val = srcCopy.getShort(srcOffset);
                        dst.putShort(dstOffset, val);
                    } else {
                        byte val = srcCopy.get(srcOffset);
                        dst.put(dstOffset, val);
                    }
                }
            }
        }

        dst.rewind();
        return dst;
    }

    public static ByteBuffer verticalFlip(ByteBuffer src, int width, int height, int channels, boolean is16Bit) {
        int bytesPerChannel = is16Bit ? 2 : 1;
        int rowSize = width * channels * bytesPerChannel;
        int totalSize = width * height * channels * bytesPerChannel;

        // Create a duplicate and rewind to read from the beginning
        ByteBuffer srcDup = src.duplicate();
        srcDup.rewind();

        ByteBuffer dst = allocator.allocate(totalSize);

        for (int y = 0; y < height; y++) {
            int srcRow = y * rowSize;
            int dstRow = (height - 1 - y) * rowSize;

            srcDup.position(srcRow).limit(srcRow + rowSize);
            ByteBuffer srcRowBuf = srcDup.slice();
            dst.position(dstRow);
            dst.put(srcRowBuf);
        }

        dst.flip();
        return dst;
    }
}
