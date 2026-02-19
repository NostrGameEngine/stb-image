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

    public enum ImageFormat {
        UNKNOWN,
        JPEG,
        PNG,
        BMP,
        GIF,
        TGA,
        HDR,
        PSD,
        PNM
    }

    public StbImageInfo(int width, int height, int channels, boolean is16Bit, ImageFormat format) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.is16Bit = is16Bit;
        this.format = format;
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

    public boolean is16Bit() {
        return is16Bit;
    }

    public ImageFormat getFormat() {
        return format;
    }

    @Override
    public String toString() {
        return String.format("StbImageInfo[%s %dx%d %d channels%s]",
            format, width, height, channels, is16Bit ? " 16-bit" : "");
    }
}
