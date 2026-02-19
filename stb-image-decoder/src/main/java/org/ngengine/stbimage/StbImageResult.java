package org.ngengine.stbimage;

import java.nio.ByteBuffer;


/**
 * Result of an image decode operation.
 * Contains the decoded pixel data and metadata.
 */
public class StbImageResult {
    private final ByteBuffer data;
    private final int width;
    private final int height;
    private final int channels;
    private final int requestedChannels;
    private final boolean is16Bit;
    private final boolean isHdr;

    public StbImageResult(ByteBuffer data, int width, int height, int channels, int requestedChannels, boolean is16Bit, boolean isHdr) {
        this.data = data;
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.requestedChannels = requestedChannels;
        this.is16Bit = is16Bit;
        this.isHdr = isHdr;
    }

    /**
     * Gets the raw pixel data as a ByteBuffer.
     * For 8-bit images: each channel is 1 byte.
     * For 16-bit images: each channel is 2 bytes (big-endian).
     * For HDR images: each channel is 4 bytes (float).
     *
     * Pixel format is interleaved: R, G, B, A, R, G, B, A...
     * Rows are stored top-to-bottom (not flipped).
     */
    public ByteBuffer getData() {
        return data;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getChannels() {
        return channels;
    }

    public int getRequestedChannels() {
        return requestedChannels;
    }

    public boolean is16Bit() {
        return is16Bit;
    }

    public boolean isHdr() {
        return isHdr;
    }

    /**
     * Gets the bytes per channel (1 for 8-bit, 2 for 16-bit, 4 for float/HDR).
     */
    public int getBytesPerChannel() {
        if (isHdr) return 4;
        if (is16Bit) return 2;
        return 1;
    }

    /**
     * Gets the total size of the pixel data in bytes.
     */
    public int getDataSize() {
        return width * height * channels * getBytesPerChannel();
    }

    /**
     * Gets a pixel at the specified coordinates.
     * The returned array contains channel values.
     * For HDR, values are floats; otherwise, they're ints (0-255 or 0-65535).
     */
    public int[] getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Coordinates out of bounds");
        }
        int offset = (y * width + x) * channels * getBytesPerChannel();
        int[] pixel = new int[channels];
        for (int i = 0; i < channels; i++) {
            if (isHdr) {
                int floatOffset = offset + i * 4;
                pixel[i] = Float.floatToIntBits(data.getFloat(floatOffset));
            } else if (is16Bit) {
                int shortOffset = offset + i * 2;
                pixel[i] = Short.toUnsignedInt(data.getShort(shortOffset));
            } else {
                pixel[i] = Byte.toUnsignedInt(data.get(offset + i));
            }
        }
        return pixel;
    }
}
