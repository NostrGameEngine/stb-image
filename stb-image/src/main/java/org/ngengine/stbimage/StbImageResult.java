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
    private final int frameIndex;

    /**
     * Creates a decoded image payload container.
     *
     * @param data raw interleaved pixel data
     * @param width image width
     * @param height image height
     * @param channels output channel count
     * @param requestedChannels originally requested channel count
     * @param is16Bit true when data uses unsigned 16-bit channels
     * @param isHdr true when data uses float channels
     */
    public StbImageResult(ByteBuffer data, int width, int height, int channels, int requestedChannels, boolean is16Bit, boolean isHdr) {
        this(data, width, height, channels, requestedChannels, is16Bit, isHdr, 0);
    }

    /**
     * Creates a decoded image payload container.
     *
     * @param data raw interleaved pixel data
     * @param width image width
     * @param height image height
     * @param channels output channel count
     * @param requestedChannels originally requested channel count
     * @param is16Bit true when data uses unsigned 16-bit channels
     * @param isHdr true when data uses float channels
     * @param frameIndex frame index for animated decodes (0 for still images)
     */
    public StbImageResult(ByteBuffer data, int width, int height, int channels, int requestedChannels, boolean is16Bit, boolean isHdr, int frameIndex) {
        this.data = data;
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.requestedChannels = requestedChannels;
        this.is16Bit = is16Bit;
        this.isHdr = isHdr;
        this.frameIndex = frameIndex;
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

    /**
     * Returns image width in pixels.
     *
     * @return width
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns image height in pixels.
     *
     * @return height
     */
    public int getHeight() {
        return height;
    }

    /**
     * Returns the effective channel count in {@link #getData()}.
     *
     * @return channel count
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Returns the channel count requested by the caller.
     *
     * @return requested channel count
     */
    public int getRequestedChannels() {
        return requestedChannels;
    }

    /**
     * Indicates 16-bit channel storage.
     *
     * @return true for 16-bit output
     */
    public boolean is16Bit() {
        return is16Bit;
    }

    /**
     * Indicates floating-point HDR storage.
     *
     * @return true for HDR float output
     */
    public boolean isFloat() {
        return isHdr;
    }


    /**
     * @deprecated Use {@link #isFloat()} instead.
     */
    @Deprecated
    public boolean isHdr() {
        return isFloat();
    }

    /**
     * Returns zero-based frame index for animated formats.
     *
     * @return frame index, 0 for still images
     */
    public int getFrameIndex() {
        return frameIndex;
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
        return StbLimits.checkedImageBufferSize(width, height, channels, getBytesPerChannel());
    }

    /**
     * Gets a pixel at the specified coordinates.
     * The returned array contains channel values.
     * For HDR, values are floats; otherwise, they're ints (0-255 or 0-65535).
     */
    public Number[] getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Coordinates out of bounds");
        }
        int offset = (y * width + x) * channels * getBytesPerChannel();
        Number[] pixel = new Number[channels];
        for (int i = 0; i < channels; i++) {
            if (isHdr) {
                int floatOffset = offset + i * 4;
                pixel[i] = data.getFloat(floatOffset);
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
