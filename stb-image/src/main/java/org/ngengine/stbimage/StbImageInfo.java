package org.ngengine.stbimage;

/**
 * Information about an image without fully decoding it.
 */
public class StbImageInfo {
    private final int width;
    private final int height;
    private final int channels;
    private final boolean is16Bit;
    private final boolean isFloat;
    private final ImageFormat format;
    private final int numFrames;

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
        this(width, height, channels, is16Bit, false, format, 1);
    }

    /**
     * Creates image metadata.
     *
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param is16Bit true for 16-bit source data
     * @param isFloat true for floating-point source data
     * @param format detected image format
     */
    public StbImageInfo(int width, int height, int channels, boolean is16Bit, boolean isFloat, ImageFormat format) {
        this(width, height, channels, is16Bit, isFloat, format, 1);
    }

    /**
     * Creates image metadata.
     *
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param is16Bit true for 16-bit source data
     * @param format detected image format
     * @param numFrames total frame count (1 for non-animated images)
     */
    public StbImageInfo(int width, int height, int channels, boolean is16Bit, ImageFormat format, int numFrames) {
        this(width, height, channels, is16Bit, false, format, numFrames);
    }

    /**
     * Creates image metadata.
     *
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param is16Bit true for 16-bit source data
     * @param isFloat true for floating-point source data
     * @param format detected image format
     * @param numFrames total frame count (1 for non-animated images)
     */
    public StbImageInfo(int width, int height, int channels, boolean is16Bit, boolean isFloat, ImageFormat format, int numFrames) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.is16Bit = is16Bit;
        this.isFloat = isFloat;
        this.format = format;
        this.numFrames = Math.max(1, numFrames);
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
     * Indicates whether source data uses floating-point channels.
     *
     * @return true for floating-point source data
     */
    public boolean isFloat() {
        return isFloat;
    }

    /**
     * @deprecated Use {@link #isFloat()} instead.
     */
    @Deprecated
    public boolean isHDR() {
        return isFloat();
    }

    /**
     * Returns the detected image format.
     *
     * @return format enum
     */
    public ImageFormat getFormat() {
        return format;
    }

    /**
     * Returns total frame count.
     *
     * @return number of frames (1 for still images)
     */
    public int getNumFrames() {
        return numFrames;
    }

    @Override
    public String toString() {
        String storage = isFloat ? " float" : (is16Bit ? " 16-bit" : "");
        return String.format("StbImageInfo[%s %dx%d %d channels%s, %d frame%s]",
            format, width, height, channels, storage,
            numFrames, numFrames == 1 ? "" : "s");
    }
}
