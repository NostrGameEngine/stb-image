package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;


/**
 * Pure Java implementation of stb_image.h
 */
public class StbImage {

    public static final int STBI_DEFAULT = 0;
    public static final int STBI_GREY = 1;
    public static final int STBI_GREY_ALPHA = 2;
    public static final int STBI_RGB = 3;
    public static final int STBI_RGB_ALPHA = 4;

    public static final int STBI_MAX_DIMENSIONS = 1 << 24;

    private final IntFunction<ByteBuffer> allocator;

    private static class DecoderRegistration {
        Class<? extends StbDecoder> decoderClass;
        Predicate<ByteBuffer> formatChecker;
        DecoderRegistration(Class<? extends StbDecoder> decoderClass, Predicate<ByteBuffer> formatChecker) {
            this.decoderClass = decoderClass;
            this.formatChecker = formatChecker;
        }
    }

    private List<DecoderRegistration> decoders = new ArrayList<>();

    public void registerDecoder(Class<? extends StbDecoder> decoderClass, Predicate<ByteBuffer> formatChecker) {
        if(decoders.stream().anyMatch(reg -> reg.decoderClass.equals(decoderClass))) {
            throw new IllegalArgumentException("Decoder already registered: " + decoderClass.getName());
        }
        decoders.add(new DecoderRegistration(decoderClass, formatChecker));
    }

    public void unregisterDecoder(Class<? extends StbDecoder> decoderClass) {
        decoders.removeIf(reg -> reg.decoderClass.equals(decoderClass));
    }

    public StbImage() {
        this(null);
    }

    public StbImage(IntFunction<ByteBuffer> allocator){
        this.allocator = allocator==null?ByteBuffer::allocate:allocator;
        registerDecoder(PngDecoder.class, PngDecoder::isPng);
        registerDecoder(JpegDecoder.class, JpegDecoder::isJpeg);
        registerDecoder(BmpDecoder.class, BmpDecoder::isBmp);
        registerDecoder(GifDecoder.class, GifDecoder::isGif);
        registerDecoder(PnmDecoder.class, PnmDecoder::isPnm);
        registerDecoder(PsdDecoder.class, PsdDecoder::isPsd);
        registerDecoder(HdrDecoder.class, HdrDecoder::isHdr);
        registerDecoder(TgaDecoder.class, TgaDecoder::isTga);
    }

    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    public StbDecoder getDecoder(ByteBuffer buffer, boolean flipVertically) {
        if (buffer.remaining() < 12) {
            throw new StbFailureException("Buffer too small to determine format");
        }
        for (DecoderRegistration reg : decoders) {
            if (reg.formatChecker.test(buffer)) {
                try {
                    StbDecoder decoder = reg.decoderClass.getDeclaredConstructor(
                            ByteBuffer.class, IntFunction.class,
                            boolean.class).newInstance(buffer, allocator, flipVertically);
                    return decoder;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate decoder: " + reg.decoderClass.getName(), e);
                }
            }
        }
        throw new StbFailureException("Unknown or unsupported image format");
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

    public static ByteBuffer convertChannels(IntFunction<ByteBuffer> allocator, ByteBuffer src, int srcChannels, int width, int height, int desiredChannels, boolean is16Bit) {
        int bytesPerChannel = is16Bit ? 2 : 1;
        int srcStride = width * srcChannels * bytesPerChannel;
        int dstStride = width * desiredChannels * bytesPerChannel;
        int srcSize = width * height * srcChannels * bytesPerChannel;
        int dstSize = width * height * desiredChannels * bytesPerChannel;

        // Always create a new buffer to ensure consistent behavior
        // First copy all source data to ensure we can read it
        src.rewind();
        ByteBuffer srcCopy = allocator.apply(src.capacity());
        srcCopy.put(src);
        srcCopy.rewind();

        // If same channels, just return the copy with proper limit
        if (srcChannels == desiredChannels) {
            srcCopy.rewind();
            return srcCopy;
        }

        // Different channel counts - perform conversion
        ByteBuffer dst = allocator.apply(dstSize);

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

    public static ByteBuffer verticalFlip(IntFunction<ByteBuffer> allocator, ByteBuffer src, int width, int height, int channels, boolean is16Bit) {
        int bytesPerChannel = is16Bit ? 2 : 1;
        int rowSize = width * channels * bytesPerChannel;
        int totalSize = width * height * channels * bytesPerChannel;

        // Create a duplicate and rewind to read from the beginning
        ByteBuffer srcDup = src.duplicate();
        srcDup.rewind();

        ByteBuffer dst = allocator.apply(totalSize);

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
