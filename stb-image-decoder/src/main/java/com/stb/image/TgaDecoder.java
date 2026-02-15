package com.stb.image;

import com.stb.image.allocator.StbAllocator;
import java.nio.ByteBuffer;

/**
 * TGA decoder supporting uncompressed and RLE compressed, grayscale and color.
 */
public class TgaDecoder {

    // TGA header field values
    private static final int TYPE_UNMAPPED_RGB = 2;
    private static final int TYPE_UNMAPPED_GRAY = 3;
    private static final int TYPE_RLE_RGB = 10;
    private static final int TYPE_RLE_GRAY = 11;
    private static final int TYPE_MAPPED_RGB = 1;
    private static final int TYPE_MAPPED_RGB_RLE = 9;

    // Origin
    private static final int ORIGIN_BOTTOM_LEFT = 0;
    private static final int ORIGIN_TOP_LEFT = 0x20;

    private ByteBuffer buffer;
    private StbAllocator allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int bitsPerPixel;
    private int channels;
    private int imageType;
    private boolean rleCompressed;
    private int colorMapOrigin;
    private int colorMapLength;
    private int colorMapDepth;
    private byte[] colorMap;
    private int xOrigin;
    private int yOrigin;

    public static StbImageInfo getInfo(ByteBuffer buffer) {
        return getInfo(buffer, StbAllocator.DEFAULT);
    }

    public static StbImageInfo getInfo(ByteBuffer buffer, StbAllocator allocator) {
        TgaDecoder decoder = new TgaDecoder(buffer.duplicate(), allocator, false);
        return decoder.decodeInfo();
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels) {
        return decode(buffer, desiredChannels, StbAllocator.DEFAULT, StbImage.isFlipVertically());
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels, StbAllocator allocator) {
        return decode(buffer, desiredChannels, allocator, StbImage.isFlipVertically());
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels, StbAllocator allocator, boolean flipVertically) {
        TgaDecoder decoder = new TgaDecoder(buffer, allocator, flipVertically);
        return decoder.decode(desiredChannels);
    }

    public TgaDecoder(ByteBuffer buffer, StbAllocator allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate();
        this.buffer.position(0);
        this.allocator = allocator != null ? allocator : StbAllocator.DEFAULT;
        this.flipVertically = flipVertically;
    }

    private int readU8() {
        return buffer.get() & 0xFF;
    }

    private int readU16LE() {
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        return b0 | (b1 << 8);
    }

    private StbImageInfo decodeInfo() {
        try {
            readHeader();

            return new StbImageInfo(width, height, channels, false, StbImageInfo.ImageFormat.TGA);
        } catch (Exception e) {
            return null;
        }
    }

    private StbImageResult decode(int desiredChannels) {
        readHeader();

        StbImage.validateDimensions(width, height);

        // Determine channels based on type
        if (imageType == TYPE_UNMAPPED_GRAY || imageType == TYPE_RLE_GRAY) {
            channels = 1;
        } else if (bitsPerPixel == 16 || bitsPerPixel == 24 || bitsPerPixel == 32) {
            channels = (bitsPerPixel == 16) ? 2 : (bitsPerPixel == 24 ? 3 : 4);
        } else {
            channels = 4;
        }

        // Read color map if present
        if (colorMapLength > 0) {
            int entrySize = (colorMapDepth + 7) / 8;
            colorMap = new byte[colorMapLength * 4]; // Convert to RGBA

            for (int i = 0; i < colorMapLength; i++) {
                int offset = i * 4;
                if (colorMapDepth == 15 || colorMapDepth == 16) {
                    int val = readU16LE();
                    colorMap[offset] = (byte) ((val & 0x1F) * 8);       // B
                    colorMap[offset + 1] = (byte) (((val >> 5) & 0x1F) * 8); // G
                    colorMap[offset + 2] = (byte) (((val >> 10) & 0x1F) * 8); // R
                    colorMap[offset + 3] = (byte) ((val & 0x8000) != 0 ? 0 : 0xFF); // A
                } else if (colorMapDepth == 24) {
                    colorMap[offset] = (byte) readU8();
                    colorMap[offset + 1] = (byte) readU8();
                    colorMap[offset + 2] = (byte) readU8();
                    colorMap[offset + 3] = (byte) 0xFF;
                } else if (colorMapDepth == 32) {
                    colorMap[offset] = (byte) readU8();
                    colorMap[offset + 1] = (byte) readU8();
                    colorMap[offset + 2] = (byte) readU8();
                    colorMap[offset + 3] = (byte) readU8();
                }
            }
        }

        // Determine if origin is top or bottom
        boolean originTop = (yOrigin & ORIGIN_TOP_LEFT) != 0;
        boolean actualFlipVertically = flipVertically ^ originTop;

        // Decode pixel data
        ByteBuffer output;
        if (rleCompressed) {
            output = decodeRLE();
        } else {
            output = decodeUncompressed();
        }

        // Convert channels
        int srcChannels = channels;
        int outChannels = (desiredChannels == 0) ?
            (channels == 1 ? 1 : 3) : desiredChannels;

        ByteBuffer result = StbImage.convertChannels(output, srcChannels, width, height, outChannels, false);

        if (actualFlipVertically) {
            result = StbImage.verticalFlip(result, width, height, outChannels, false);
        }

        return new StbImageResult(result, width, height, outChannels, desiredChannels, false, false);
    }

    private void readHeader() {
        // ID length
        int idLength = readU8();

        // Color map type
        int colorMapType = readU8();

        // Image type
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

        // Image descriptor
        int imageDescriptor = readU8();
        yOrigin = (imageDescriptor & 0x20) | (yOrigin & 0x20);

        // Skip image ID
        buffer.position(buffer.position() + idLength);

        // Determine compression
        rleCompressed = (imageType == TYPE_RLE_RGB || imageType == TYPE_RLE_GRAY);
    }

    private ByteBuffer decodeUncompressed() {
        ByteBuffer output = allocator.allocate(width * height * channels);

        int bytesPerPixel = (bitsPerPixel + 7) / 8;

        if (imageType == TYPE_MAPPED_RGB || imageType == TYPE_MAPPED_RGB_RLE || colorMapLength > 0) {
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

                    int p = idx * 4;
                    int outPos = (y * width + x) * channels;

                    if (colorMap != null && idx < colorMap.length / 4) {
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
        output.limit(width * height * channels);
        return output;
    }

    private ByteBuffer decodeRLE() {
        ByteBuffer output = allocator.allocate(width * height * channels);
        int pixelCount = width * height;
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

                    if (colorMap != null) {
                        // Paletted - use first byte as index
                        int idx = pixel[0] & 0xFF;
                        int p = idx * 4;
                        for (int c = 0; c < channels; c++) {
                            output.put(outPos + c, colorMap[p + c]);
                        }
                    } else if (bitsPerPixel == 24) {
                        // TGA stores as BGR
                        output.put(outPos, pixel[2]);     // R
                        output.put(outPos + 1, pixel[1]); // G
                        output.put(outPos + 2, pixel[0]); // B
                    } else if (bitsPerPixel == 32) {
                        // TGA stores as BGRA
                        output.put(outPos, pixel[2]);     // R
                        output.put(outPos + 1, pixel[1]); // G
                        output.put(outPos + 2, pixel[0]); // B
                        if (channels == 4) {
                            output.put(outPos + 3, pixel[3]); // A
                        }
                    } else if (bitsPerPixel == 16) {
                        output.put(outPos, pixel[0]);
                        if (channels == 2) {
                            output.put(outPos + 1, pixel[1]);
                        }
                    } else {
                        // grayscale (bitsPerPixel == 8)
                        output.put(outPos, pixel[0]);
                    }
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

                    if (colorMap != null) {
                        int idx = pixel[0] & 0xFF;
                        int p = idx * 4;
                        for (int c = 0; c < channels; c++) {
                            output.put(outPos + c, colorMap[p + c]);
                        }
                    } else if (bitsPerPixel == 24) {
                        // TGA stores as BGR
                        output.put(outPos, pixel[2]);     // R
                        output.put(outPos + 1, pixel[1]); // G
                        output.put(outPos + 2, pixel[0]); // B
                    } else if (bitsPerPixel == 32) {
                        // TGA stores as BGRA
                        output.put(outPos, pixel[2]);     // R
                        output.put(outPos + 1, pixel[1]); // G
                        output.put(outPos + 2, pixel[0]); // B
                        if (channels == 4) {
                            output.put(outPos + 3, pixel[3]); // A
                        }
                    } else if (bitsPerPixel == 16) {
                        output.put(outPos, pixel[0]);
                        if (channels == 2) {
                            output.put(outPos + 1, pixel[1]);
                        }
                    } else {
                        // grayscale (bitsPerPixel == 8) - RAW packet
                        output.put(outPos, pixel[0]);
                    }
                    pixelIndex++;
                }
            }
        }

        // Set limit to the actual data size since we use absolute positioning
        output.limit(width * height * channels);
        return output;
    }
}
