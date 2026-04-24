package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

public final class StbUtils {

    /**
     * Converts interleaved pixel channels between 1/2/3/4-channel layouts.
     *
     * @param allocator destination allocator
     * @param src source interleaved pixel buffer
     * @param srcChannels source channel count
     * @param width image width
     * @param height image height
     * @param desiredChannels target channel count
     * @param is16Bit true for 16-bit channel elements
     * @return converted buffer
     */
    public static ByteBuffer convertChannels(IntFunction<ByteBuffer> allocator, ByteBuffer src, int srcChannels, int width, int height, int desiredChannels, boolean is16Bit) {
        if (srcChannels < 1 || srcChannels > 4 || desiredChannels < 1 || desiredChannels > 4) {
            throw new StbFailureException("Unsupported format conversion");
        }

        int bytesPerChannel = is16Bit ? 2 : 1;
        int srcSize = StbLimits.checkedImageBufferSize(width, height, srcChannels, bytesPerChannel);
        int dstSize = StbLimits.checkedImageBufferSize(width, height, desiredChannels, bytesPerChannel);

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

        int pixels = StbLimits.checkedPixelCount(width, height);
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

    /**
     * Flips interleaved pixel data vertically.
     *
     * @param allocator destination allocator
     * @param src source pixels
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param is16Bit true for 16-bit elements (2 bytes per channel)
     * @return vertically flipped buffer
     */
    public static ByteBuffer verticalFlip(IntFunction<ByteBuffer> allocator, ByteBuffer src, int width, int height, int channels, boolean is16Bit) {
        return verticalFlip(allocator, src, width, height, channels, is16Bit ? 2 : 1);
    }

    /**
     * Flips interleaved pixel data vertically using explicit channel byte width.
     *
     * @param allocator destination allocator
     * @param src source pixels
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param bytesPerChannel bytes per channel element
     * @return vertically flipped buffer
     */
    public static ByteBuffer verticalFlip(IntFunction<ByteBuffer> allocator, ByteBuffer src, int width, int height, int channels, int bytesPerChannel) {
        int rowSize = StbLimits.checkedImageBufferSize(width, 1, channels, bytesPerChannel);
        int totalSize = StbLimits.checkedImageBufferSize(width, height, channels, bytesPerChannel);

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
