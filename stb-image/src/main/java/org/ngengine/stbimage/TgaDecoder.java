package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;


/**
 * TGA decoder supporting uncompressed and RLE compressed, grayscale and color.
 */
public class TgaDecoder implements StbDecoder {

    // TGA header field values
    private static final int TYPE_UNMAPPED_RGB = 2;
    private static final int TYPE_UNMAPPED_GRAY = 3;
    private static final int TYPE_RLE_RGB = 10;
    private static final int TYPE_RLE_GRAY = 11;
    private static final int TYPE_MAPPED_RGB = 1;
    private static final int TYPE_MAPPED_RGB_RLE = 9;

    // Origin
    private static final int ORIGIN_TOP_LEFT = 0x20;

    private final ByteBuffer buffer;
    private final IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int bitsPerPixel;
    private int channels;
    private int colorMapType;
    private int imageType;
    private boolean rleCompressed;
    private boolean originTop;
    private int colorMapOrigin;
    private int colorMapLength;
    private int colorMapDepth;
    private byte[] colorMap;
    private int xOrigin;
    private int yOrigin;


 
    /**
     * {@inheritDoc}
     */
    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    /**
     * Creates a TGA decoder instance.
     *
     * @param buffer source data
     * @param allocator output allocator
     * @param flipVertically true to vertically flip decoded output
     */
    public TgaDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        StbLimits.lock(); // Lock limits on decoder initialization
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        this.buffer.position(0);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
    }

    /**
     * Performs a lightweight TGA probe.
     *
     * @param src source bytes
     * @return true when the source can be considered a potential TGA payload
     */
    public static boolean isTga(ByteBuffer src) {
        if (src.remaining() < 18) {
            return false;
        }
        ByteBuffer probe = src.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int p = probe.position();
        int remaining = probe.remaining();
        try {
            return isPlausibleHeader(
                probe.get(p) & 0xFF,
                probe.get(p + 1) & 0xFF,
                probe.get(p + 2) & 0xFF,
                Short.toUnsignedInt(probe.getShort(p + 5)),
                probe.get(p + 7) & 0xFF,
                Short.toUnsignedInt(probe.getShort(p + 12)),
                Short.toUnsignedInt(probe.getShort(p + 14)),
                probe.get(p + 16) & 0xFF,
                remaining
            );
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int readU8() {
        ensureRemaining(1);
        return buffer.get() & 0xFF;
    }

    private int readU16LE() {
        ensureRemaining(2);
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        return b0 | (b1 << 8);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageInfo info() {
        int savedPosition = buffer.position();
        try {
            buffer.position(0);
            readHeader();
            channels = getInfoChannels();

            return new StbImageInfo(width, height, channels, false, StbImageInfo.ImageFormat.TGA);
        } catch (Exception e) {
            return null;
        } finally {
            buffer.position(savedPosition);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        buffer.position(0);
        readHeader();

        StbLimits.validateDimensions(width, height);

        // Determine channels based on type
        channels = getInfoChannels();

        // Read color map if present
        if (isColorMapped()) {
            colorMap = new byte[colorMapLength * channels];

            for (int i = 0; i < colorMapLength; i++) {
                int offset = i * channels;
                if (colorMapDepth == 8) {
                    colorMap[offset] = (byte) readU8();
                } else if (colorMapDepth == 15 || colorMapDepth == 16) {
                    putRgb16(colorMap, offset, readU16LE());
                } else if (colorMapDepth == 24) {
                    byte b = (byte) readU8();
                    colorMap[offset + 1] = (byte) readU8();
                    colorMap[offset] = (byte) readU8();
                    colorMap[offset + 2] = b;
                } else if (colorMapDepth == 32) {
                    byte b = (byte) readU8();
                    colorMap[offset + 1] = (byte) readU8();
                    colorMap[offset] = (byte) readU8();
                    colorMap[offset + 2] = b;
                    colorMap[offset + 3] = (byte) readU8();
                }
            }
        }

        boolean actualFlipVertically = flipVertically || !originTop;

        // Decode pixel data
        ByteBuffer output;
        if (rleCompressed) {
            output = decodeRLE();
        } else {
            output = decodeUncompressed();
        }

        // Convert channels
        int srcChannels = channels;
        int outChannels = (desiredChannels == 0) ? channels : desiredChannels;

        ByteBuffer result = StbUtils.convertChannels(getAllocator(),output, srcChannels, width, height, outChannels, false);

        if (actualFlipVertically) {
            result = StbUtils.verticalFlip(getAllocator(),result, width, height, outChannels, false);
        }

        return new StbImageResult(result, width, height, outChannels, desiredChannels, false, false);
    }

    private void readHeader() {
        int idLength = readU8();

        colorMapType = readU8();

        imageType = readU8();

        // Color map specification
        colorMapOrigin = readU16LE();
        colorMapLength = readU16LE();
        colorMapDepth = readU8();

        // Image specification
        xOrigin = readU16LE();
        yOrigin = readU16LE();
        width = readU16LE();
        height = readU16LE();
        bitsPerPixel = readU8();

        int imageDescriptor = readU8();
        originTop = (imageDescriptor & ORIGIN_TOP_LEFT) != 0;

        validateHeaderValues(
            idLength,
            colorMapType,
            imageType,
            colorMapLength,
            colorMapDepth,
            width,
            height,
            bitsPerPixel,
            buffer.limit()
        );
        buffer.position(buffer.position() + idLength);

        rleCompressed = (imageType == TYPE_RLE_RGB || imageType == TYPE_RLE_GRAY || imageType == TYPE_MAPPED_RGB_RLE);
    }

    private int getInfoChannels() {
        if (imageType == TYPE_UNMAPPED_GRAY || imageType == TYPE_RLE_GRAY) {
            return bitsPerPixel == 16 ? 2 : 1;
        }
        if (isColorMapped()) {
            return getPaletteChannels();
        }
        if (bitsPerPixel == 15 || bitsPerPixel == 16 || bitsPerPixel == 24) {
            return 3;
        }
        if (bitsPerPixel == 32) {
            return 4;
        }
        throw new StbFailureException("Unsupported TGA pixel depth: " + bitsPerPixel);
    }

    private int getPaletteChannels() {
        if (colorMapDepth == 8) {
            return 1;
        }
        if (colorMapDepth == 15 || colorMapDepth == 16 || colorMapDepth == 24) {
            return 3;
        }
        if (colorMapDepth == 32) {
            return 4;
        }
        throw new StbFailureException("Unsupported TGA palette depth: " + colorMapDepth);
    }

    private ByteBuffer decodeUncompressed() {
        int outSize = StbLimits.checkedImageBufferSize(width, height, channels, 1);
        ByteBuffer output = allocator.apply(outSize);

        if (isColorMapped()) {
            // Paletted image
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx;
                    if (bitsPerPixel <= 8) {
                        idx = readU8();
                    } else if (bitsPerPixel == 16) {
                        idx = readU16LE();
                    } else {
                        idx = readU8();
                    }

                    int outPos = (y * width + x) * channels;
                    int p = paletteOffsetForIndex(idx);
                    if (p >= 0) {
                        for (int c = 0; c < channels; c++) {
                            output.put(outPos + c, colorMap[p + c]);
                        }
                    } else {
                        // Default
                        for (int c = 0; c < channels; c++) {
                            output.put(outPos + c, (byte) 0);
                        }
                    }
                }
            }
        } else {
            // Direct color or grayscale
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int outPos = (y * width + x) * channels;

                    if (bitsPerPixel == 16 && channels == 2) {
                        // Grayscale with alpha
                        int val = readU16LE();
                        output.put(outPos, (byte) ((val & 0xFF)));
                        output.put(outPos + 1, (byte) ((val >> 8) & 0xFF));
                    } else if ((bitsPerPixel == 15 || bitsPerPixel == 16) && channels == 3) {
                        putRgb16(output, outPos, readU16LE());
                    } else if (bitsPerPixel == 24) {
                        // TGA stores as BGR
                        byte b = (byte) readU8();
                        byte g = (byte) readU8();
                        byte r = (byte) readU8();
                        output.put(outPos, r);     // R
                        output.put(outPos + 1, g); // G
                        output.put(outPos + 2, b); // B
                    } else if (bitsPerPixel == 32) {
                        // BGRA in file, convert to RGBA
                        byte b = (byte) readU8();
                        byte g = (byte) readU8();
                        byte r = (byte) readU8();
                        output.put(outPos, r);     // R
                        output.put(outPos + 1, g); // G
                        output.put(outPos + 2, b); // B
                        if (channels == 4) {
                            output.put(outPos + 3, (byte) readU8()); // A
                        }
                    } else if (bitsPerPixel == 8 && channels == 1) {
                        // Grayscale
                        output.put(outPos, (byte) readU8());
                    } else if (bitsPerPixel == 8 && channels == 3) {
                        // RGB (rare case)
                        output.put(outPos, (byte) readU8());
                        output.put(outPos + 1, (byte) readU8());
                        output.put(outPos + 2, (byte) readU8());
                    }
                }
            }
        }

        // Set limit to actual data size since we use absolute positioning
        output.limit(outSize);
        return output;
    }

    private ByteBuffer decodeRLE() {
        int outSize = StbLimits.checkedImageBufferSize(width, height, channels, 1);
        ByteBuffer output = allocator.apply(outSize);
        int pixelCount = StbLimits.checkedPixelCount(width, height);
        int pixelIndex = 0;
        int bytesPerPixel = Math.max(1, (bitsPerPixel + 7) / 8);

        while (pixelIndex < pixelCount) {
            int packetHeader = readU8();
            int packetLength = (packetHeader & 0x7F) + 1;

            if ((packetHeader & 0x80) != 0) {
                // RLE packet
                byte[] pixel = new byte[bytesPerPixel];
                for (int i = 0; i < bytesPerPixel; i++) {
                    pixel[i] = (byte) readU8();
                }

                for (int i = 0; i < packetLength && pixelIndex < pixelCount; i++) {
                    int outPos = (pixelIndex / width) * width * channels + (pixelIndex % width) * channels;

                    putDecodedPixel(output, outPos, pixel);
                    pixelIndex++;
                }
            } else {
                // Raw packet
                for (int i = 0; i < packetLength && pixelIndex < pixelCount; i++) {
                    byte[] pixel = new byte[bytesPerPixel];
                    for (int j = 0; j < bytesPerPixel; j++) {
                        pixel[j] = (byte) readU8();
                    }

                    int outPos = (pixelIndex / width) * width * channels + (pixelIndex % width) * channels;

                    putDecodedPixel(output, outPos, pixel);
                    pixelIndex++;
                }
            }
        }

        // Set limit to the actual data size since we use absolute positioning
        output.limit(outSize);
        return output;
    }

    private int paletteOffsetForIndex(int idx) {
        if (colorMap == null || colorMapLength <= 0) {
            return -1;
        }
        int localIndex = idx - colorMapOrigin;
        if (localIndex < 0 || localIndex >= colorMapLength) {
            return -1;
        }
        return localIndex * channels;
    }

    private boolean isColorMapped() {
        return colorMapType == 1;
    }

    private static void validateHeaderValues(
        int idLength,
        int colorMapType,
        int imageType,
        int colorMapLength,
        int colorMapDepth,
        int width,
        int height,
        int bitsPerPixel,
        int available
    ) {
        if (!isPlausibleHeader(
            idLength,
            colorMapType,
            imageType,
            colorMapLength,
            colorMapDepth,
            width,
            height,
            bitsPerPixel,
            available
        )) {
            throw new StbFailureException("Invalid TGA header");
        }
    }

    private static boolean isPlausibleHeader(
        int idLength,
        int colorMapType,
        int imageType,
        int colorMapLength,
        int colorMapDepth,
        int width,
        int height,
        int bitsPerPixel,
        int available
    ) {
        if (colorMapType != 0 && colorMapType != 1) {
            return false;
        }
        if (width < 1 || height < 1) {
            return false;
        }
        if (18 + idLength > available) {
            return false;
        }

        if (colorMapType == 1) {
            if (imageType != TYPE_MAPPED_RGB && imageType != TYPE_MAPPED_RGB_RLE) {
                return false;
            }
            if (colorMapLength < 1) {
                return false;
            }
            if (bitsPerPixel != 8 && bitsPerPixel != 16) {
                return false;
            }
            return isPaletteDepthSupported(colorMapDepth);
        }

        if (imageType != TYPE_UNMAPPED_RGB && imageType != TYPE_UNMAPPED_GRAY
            && imageType != TYPE_RLE_RGB && imageType != TYPE_RLE_GRAY) {
            return false;
        }
        if (imageType == TYPE_UNMAPPED_GRAY || imageType == TYPE_RLE_GRAY) {
            return bitsPerPixel == 8 || bitsPerPixel == 16;
        }
        return bitsPerPixel == 15 || bitsPerPixel == 16 || bitsPerPixel == 24 || bitsPerPixel == 32;
    }

    private static boolean isPaletteDepthSupported(int colorMapDepth) {
        return colorMapDepth == 8 || colorMapDepth == 15 || colorMapDepth == 16
            || colorMapDepth == 24 || colorMapDepth == 32;
    }

    private static void validatePaletteDepth(int colorMapDepth) {
        if (!isPaletteDepthSupported(colorMapDepth)) {
            throw new StbFailureException("Unsupported TGA palette depth: " + colorMapDepth);
        }
    }

    private void ensureRemaining(int count) {
        if (buffer.remaining() < count) {
            throw new StbFailureException("Truncated TGA data");
        }
    }

    private void putDecodedPixel(ByteBuffer output, int outPos, byte[] pixel) {
        if (colorMap != null) {
            int idx = (bitsPerPixel == 16)
                ? ((pixel[0] & 0xFF) | ((pixel[1] & 0xFF) << 8))
                : (pixel[0] & 0xFF);
            int p = paletteOffsetForIndex(idx);
            if (p >= 0) {
                for (int c = 0; c < channels; c++) {
                    output.put(outPos + c, colorMap[p + c]);
                }
            } else {
                for (int c = 0; c < channels; c++) {
                    output.put(outPos + c, (byte) 0);
                }
            }
        } else if ((bitsPerPixel == 15 || bitsPerPixel == 16) && channels == 3) {
            putRgb16(output, outPos, (pixel[0] & 0xFF) | ((pixel[1] & 0xFF) << 8));
        } else if (bitsPerPixel == 24) {
            output.put(outPos, pixel[2]);
            output.put(outPos + 1, pixel[1]);
            output.put(outPos + 2, pixel[0]);
        } else if (bitsPerPixel == 32) {
            output.put(outPos, pixel[2]);
            output.put(outPos + 1, pixel[1]);
            output.put(outPos + 2, pixel[0]);
            output.put(outPos + 3, pixel[3]);
        } else if (bitsPerPixel == 16) {
            output.put(outPos, pixel[0]);
            output.put(outPos + 1, pixel[1]);
        } else {
            output.put(outPos, pixel[0]);
        }
    }

    private void putRgb16(ByteBuffer output, int outPos, int value) {
        int r = (value >> 10) & 31;
        int g = (value >> 5) & 31;
        int b = value & 31;
        output.put(outPos, (byte) ((r * 255) / 31));
        output.put(outPos + 1, (byte) ((g * 255) / 31));
        output.put(outPos + 2, (byte) ((b * 255) / 31));
    }

    private void putRgb16(byte[] output, int outPos, int value) {
        int r = (value >> 10) & 31;
        int g = (value >> 5) & 31;
        int b = value & 31;
        output[outPos] = (byte) ((r * 255) / 31);
        output[outPos + 1] = (byte) ((g * 255) / 31);
        output[outPos + 2] = (byte) ((b * 255) / 31);
    }
}
