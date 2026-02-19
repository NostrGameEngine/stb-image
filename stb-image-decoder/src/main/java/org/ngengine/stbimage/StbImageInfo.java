package org.ngengine.stbimage;

/**
 * Information about an image without fully decoding it.
 */
public class StbImageInfo {
    private final int width;
    private final int height;
    private final int channels;
    private final boolean is16Bit;
    private final ImageFormat format;

    /**
     * Supported image formats known by this decoder set.
     */
    public enum ImageFormat {
        UNKNOWN,
        JPEG,
        PNG,
        BMP,
        GIF,
        TGA,
        HDR,
        PSD,
        PNM,
        PIC
    }

    /**
     * Creates image metadata.
     *
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param is16Bit true for 16-bit source data
     * @param format detected image format
     */
    public StbImageInfo(int width, int height, int channels, boolean is16Bit, ImageFormat format) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.is16Bit = is16Bit;
        this.format = format;
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
     * Returns channel count in decoded source/default representation.
     *
     * @return channel count
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Indicates whether source data is 16-bit per channel.
     *
     * @return true for 16-bit
     */
    public boolean is16Bit() {
        return is16Bit;
    }

    /**
     * Returns the detected image format.
     *
     * @return format enum
     */
    public ImageFormat getFormat() {
        return format;
    }

    @Override
    public String toString() {
        return String.format("StbImageInfo[%s %dx%d %d channels%s]",
            format, width, height, channels, is16Bit ? " 16-bit" : "");
    }
}
