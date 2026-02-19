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
    private boolean convertIphonePngToRgb = false;
    private boolean unpremultiplyOnLoad = false;

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
        registerDecoder(PicDecoder.class, PicDecoder::isPic);
        registerDecoder(TgaDecoder.class, TgaDecoder::isTga);
    }

    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    public void setConvertIphonePngToRgb(boolean convertIphonePngToRgb) {
        this.convertIphonePngToRgb = convertIphonePngToRgb;
    }

    public boolean isConvertIphonePngToRgb() {
        return convertIphonePngToRgb;
    }

    public void setUnpremultiplyOnLoad(boolean unpremultiplyOnLoad) {
        this.unpremultiplyOnLoad = unpremultiplyOnLoad;
    }

    public boolean isUnpremultiplyOnLoad() {
        return unpremultiplyOnLoad;
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
                    if (decoder instanceof PngDecoder) {
                        PngDecoder pngDecoder = (PngDecoder) decoder;
                        pngDecoder.setConvertIphonePngToRgb(convertIphonePngToRgb);
                        pngDecoder.setUnpremultiplyOnLoad(unpremultiplyOnLoad);
                    }
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
        if (srcChannels < 1 || srcChannels > 4 || desiredChannels < 1 || desiredChannels > 4) {
            throw new StbFailureException("Unsupported format conversion");
        }

        int bytesPerChannel = is16Bit ? 2 : 1;
        int srcSize = width * height * srcChannels * bytesPerChannel;
        int dstSize = width * height * desiredChannels * bytesPerChannel;

        ByteBuffer in = src.duplicate();
        in.position(0);
        if (in.remaining() < srcSize) {
            throw new StbFailureException("Not enough source data for channel conversion");
        }

        ByteBuffer out = allocator.apply(dstSize);
        if (srcChannels == desiredChannels) {
            for (int i = 0; i < srcSize; i++) {
                out.put(i, in.get(i));
            }
            out.limit(dstSize);
            out.position(0);
            return out;
        }

        int pixels = width * height;
        for (int i = 0; i < pixels; i++) {
            int srcBase = i * srcChannels * bytesPerChannel;
            int dstBase = i * desiredChannels * bytesPerChannel;

            int s0 = readChannel(in, srcBase, 0, is16Bit);
            int s1 = srcChannels > 1 ? readChannel(in, srcBase, 1, is16Bit) : 0;
            int s2 = srcChannels > 2 ? readChannel(in, srcBase, 2, is16Bit) : 0;
            int s3 = srcChannels > 3 ? readChannel(in, srcBase, 3, is16Bit) : 0;
            int max = is16Bit ? 0xFFFF : 0xFF;

            switch ((srcChannels << 3) + desiredChannels) {
                case (1 << 3) + 2:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, max, is16Bit);
                    break;
                case (1 << 3) + 3:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, s0, is16Bit);
                    writeChannel(out, dstBase, 2, s0, is16Bit);
                    break;
                case (1 << 3) + 4:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, s0, is16Bit);
                    writeChannel(out, dstBase, 2, s0, is16Bit);
                    writeChannel(out, dstBase, 3, max, is16Bit);
                    break;
                case (2 << 3) + 1:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    break;
                case (2 << 3) + 3:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, s0, is16Bit);
                    writeChannel(out, dstBase, 2, s0, is16Bit);
                    break;
                case (2 << 3) + 4:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, s0, is16Bit);
                    writeChannel(out, dstBase, 2, s0, is16Bit);
                    writeChannel(out, dstBase, 3, s1, is16Bit);
                    break;
                case (3 << 3) + 4:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, s1, is16Bit);
                    writeChannel(out, dstBase, 2, s2, is16Bit);
                    writeChannel(out, dstBase, 3, max, is16Bit);
                    break;
                case (3 << 3) + 1:
                    writeChannel(out, dstBase, 0, computeY(s0, s1, s2), is16Bit);
                    break;
                case (3 << 3) + 2:
                    writeChannel(out, dstBase, 0, computeY(s0, s1, s2), is16Bit);
                    writeChannel(out, dstBase, 1, max, is16Bit);
                    break;
                case (4 << 3) + 1:
                    writeChannel(out, dstBase, 0, computeY(s0, s1, s2), is16Bit);
                    break;
                case (4 << 3) + 2:
                    writeChannel(out, dstBase, 0, computeY(s0, s1, s2), is16Bit);
                    writeChannel(out, dstBase, 1, s3, is16Bit);
                    break;
                case (4 << 3) + 3:
                    writeChannel(out, dstBase, 0, s0, is16Bit);
                    writeChannel(out, dstBase, 1, s1, is16Bit);
                    writeChannel(out, dstBase, 2, s2, is16Bit);
                    break;
                default:
                    throw new StbFailureException("Unsupported format conversion");
            }
        }

        out.limit(dstSize);
        out.position(0);
        return out;
    }

    public static ByteBuffer verticalFlip(IntFunction<ByteBuffer> allocator, ByteBuffer src, int width, int height, int channels, boolean is16Bit) {
        return verticalFlip(allocator, src, width, height, channels, is16Bit ? 2 : 1);
    }

    public static ByteBuffer verticalFlip(IntFunction<ByteBuffer> allocator, ByteBuffer src, int width, int height, int channels, int bytesPerChannel) {
        int rowSize = width * channels * bytesPerChannel;
        int totalSize = width * height * channels * bytesPerChannel;

        ByteBuffer in = src.duplicate();
        in.position(0);
        if (in.remaining() < totalSize) {
            throw new StbFailureException("Not enough image data for vertical flip");
        }

        ByteBuffer dst = allocator.apply(totalSize);
        byte[] row = new byte[rowSize];

        for (int y = 0; y < height; y++) {
            int srcRow = y * rowSize;
            int dstRow = (height - 1 - y) * rowSize;
            in.position(srcRow);
            in.get(row, 0, rowSize);
            dst.position(dstRow);
            dst.put(row);
        }

        dst.limit(totalSize);
        dst.position(0);
        return dst;
    }

    private static int readChannel(ByteBuffer data, int base, int channel, boolean is16Bit) {
        int off = base + (is16Bit ? channel * 2 : channel);
        return is16Bit ? Short.toUnsignedInt(data.getShort(off)) : Byte.toUnsignedInt(data.get(off));
    }

    private static void writeChannel(ByteBuffer data, int base, int channel, int value, boolean is16Bit) {
        int off = base + (is16Bit ? channel * 2 : channel);
        if (is16Bit) {
            data.putShort(off, (short) value);
        } else {
            data.put(off, (byte) value);
        }
    }

    private static int computeY(int r, int g, int b) {
        return ((r * 77) + (g * 150) + (29 * b)) >> 8;
    }
}
