package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;


/**
 * BMP decoder supporting 1/4/8/24/32-bit, uncompressed and RLE compressed.
 */
public class BmpDecoder implements StbDecoder{

    // Compression types
    private static final int BI_RGB = 0;
    private static final int BI_RLE8 = 1;
    private static final int BI_RLE4 = 2;
    private static final int BI_BITFIELDS = 3;

    private ByteBuffer buffer;
    private int pos;
    private IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int bitsPerPixel;
    private int channels;
    private int compression;
    private byte[] palette;
    private int paletteSize;
    private int maskR;
    private int maskG;
    private int maskB;
    private int maskA;

    /**
     * Tests if the buffer starts with BMP magic bytes.
     *
     * @param buffer source bytes
     * @return true if BMP signature matches
     */
    public static boolean isBmp(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return false;
        buffer = buffer.duplicate();
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        return b0 == 'B' && b1 == 'M';
    }

    /**
     * Creates a BMP decoder instance.
     *
     * @param buffer source data
     * @param allocator output allocator
     * @param flipVertically true to flip decoded rows
     */
    public BmpDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        this.buffer.position(0);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageInfo info() {
        if (!readSignature()) {
            return null;
        }

        // Skip to DIB header (file header is 14 bytes)
        pos = 14;
        int headerSize = readU32LE();

        switch (headerSize) {
            case 12: // BITMAPCOREHEADER (OS/2)
                width = readU16LE();
                height = readU16LE();
                break;
            case 40: // BITMAPINFOHEADER
            case 52: // BITMAPV4HEADER
            case 108: // BITMAPV5HEADER
                width = readS32LE();
                int heightTmp = readS32LE();
                height = Math.abs(heightTmp);
                break;
            default:
                return null;
        }

        pos += 2; // planes
        int bpp = readU16LE();
        compression = readU32LE();

        // Determine channels based on bits per pixel
        int infoChannels;
        if (bpp <= 8) {
            infoChannels = 1;
        } else if (bpp == 24 || bpp == 32) {
            infoChannels = 3;
            if (bpp == 32) infoChannels = 4;
        } else {
            infoChannels = 3;
        }

        return new StbImageInfo(width, height, infoChannels, false, StbImageInfo.ImageFormat.BMP);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        if (!readSignature()) {
            throw new StbFailureException("Not a BMP file");
        }

        // Read file header (14 bytes total)
        // bytes 0-1: signature "BM" (already read by readSignature)
        // bytes 2-5: file size
        int fileSize = readU32LE();
        // bytes 6-9: reserved (2 bytes actually, but aligned to 4)
        pos += 4;
        // bytes 10-13: data offset
        int dataOffset = readU32LE();

        // Read DIB header
        int headerSize = readU32LE();

        switch (headerSize) {
            case 12: // BITMAPCOREHEADER (OS/2)
                width = readU16LE();
                height = readU16LE();
                pos += 2; // planes
                bitsPerPixel = readU16LE();
                compression = BI_RGB;
                paletteSize = 1 << bitsPerPixel;
                break;
            case 40: // BITMAPINFOHEADER
            case 52: // BITMAPV2INFOHEADER
            case 56: // BITMAPV3INFOHEADER
            case 108: // BITMAPV4HEADER
            case 124: // BITMAPV5HEADER
                width = readS32LE();
                int heightTmp = readS32LE();
                height = Math.abs(heightTmp);
                boolean topDown = heightTmp < 0;
                if (topDown) {
                    flipVertically = !flipVertically;
                }
                pos += 2; // planes
                bitsPerPixel = readU16LE();
                compression = readU32LE();
                readU32LE(); // imageSize
                readS32LE(); // x ppm
                readS32LE(); // y ppm
                paletteSize = readU32LE(); // colors used
                readU32LE(); // important colors
                int extra = headerSize - 40;
                if (extra >= 12) {
                    maskR = readU32LE();
                    maskG = readU32LE();
                    maskB = readU32LE();
                    extra -= 12;
                }
                if (extra >= 4) {
                    maskA = readU32LE();
                    extra -= 4;
                }
                pos += Math.max(0, extra);
                break;
            default:
                throw new StbFailureException("Unsupported BMP header size: " + headerSize);
        }

        StbImage.validateDimensions(width, height);

        if (bitsPerPixel <= 8) {
            channels = 4; // Always decode to RGBA for palette images
            // Seek to start of palette (right after DIB header)
            pos = 14 + headerSize;
            // Calculate actual palette entries based on data offset
            int paletteEntrySize = (headerSize == 12) ? 3 : 4;
            int maxPaletteBytes = dataOffset - pos;
            int actualPaletteEntries = maxPaletteBytes / paletteEntrySize;
            paletteSize = Math.min(paletteSize, actualPaletteEntries);
            paletteSize = Math.max(paletteSize, 1); // At least 1 entry
        } else if (bitsPerPixel == 24) {
            channels = 3;
        } else if (bitsPerPixel == 32) {
            channels = 4;
        } else {
            throw new StbFailureException("Unsupported bits per pixel: " + bitsPerPixel);
        }

        int rowStride = ((width * bitsPerPixel + 31) >> 5) << 2;

        if (compression != BI_RGB && compression != BI_RLE8 && compression != BI_RLE4 && compression != BI_BITFIELDS) {
            throw new StbFailureException("Unsupported BMP compression: " + compression);
        }
        if (compression == BI_BITFIELDS && bitsPerPixel != 16 && bitsPerPixel != 32) {
            throw new StbFailureException("BMP bitfields requires 16 or 32 bpp");
        }
        if (compression == BI_RGB) {
            if (bitsPerPixel == 16 && (maskR | maskG | maskB) == 0) {
                // stb default for 16-bit BI_RGB
                maskR = 31 << 10;
                maskG = 31 << 5;
                maskB = 31;
            } else if (bitsPerPixel == 32 && (maskR | maskG | maskB | maskA) == 0) {
                // stb default for 32-bit BI_RGB
                maskR = 0x00FF0000;
                maskG = 0x0000FF00;
                maskB = 0x000000FF;
                maskA = 0xFF000000;
            }
        }

        // Read palette BEFORE seeking to pixel data (for palette images)
        // Palette is stored right after DIB header
        if (bitsPerPixel <= 8) {
            channels = 4; // Always decode to RGBA for palette images
            // Calculate actual palette entries based on data offset
            int paletteStart = 14 + headerSize;
            int paletteEntrySize = (headerSize == 12) ? 3 : 4;
            int maxPaletteBytes = dataOffset - paletteStart;
            int actualPaletteEntries = maxPaletteBytes / paletteEntrySize;
            paletteSize = Math.min(paletteSize, actualPaletteEntries);
            paletteSize = Math.max(paletteSize, 1); // At least 1 entry

            pos = paletteStart; // Seek to start of palette
            int maxPaletteEntries = Math.min(paletteSize, 256); // Limit to reasonable size
            palette = new byte[maxPaletteEntries * 4];
            for (int i = 0; i < maxPaletteEntries; i++) {
                int base = i * 4;
                if (headerSize == 12) {
                    palette[base + 0] = (byte) readU8(); // B
                    palette[base + 1] = (byte) readU8(); // G
                    palette[base + 2] = (byte) readU8(); // R
                    palette[base + 3] = (byte) -1; // A (fully opaque)
                } else {
                    palette[base + 0] = (byte) readU8(); // B
                    palette[base + 1] = (byte) readU8(); // G
                    palette[base + 2] = (byte) readU8(); // R
                    byte alpha = (byte) readU8(); // A
                    // Treat alpha=0 as fully opaque (common in BMP files)
                    palette[base + 3] = (alpha == 0) ? (byte) 0xFF : alpha;
                }
            }
        }

        // Seek to pixel data
        pos = dataOffset;

        // Position is now at start of pixel data
        // (pos was advanced by the palette reading loop)

        // Decode based on compression
        ByteBuffer output;
        if (compression == BI_RLE8) {
            output = decodeRLE8();
        } else if (compression == BI_RLE4) {
            output = decodeRLE4();
        } else {
            output = decodeUncompressed(rowStride);
        }

        // Convert channels
        if (desiredChannels == 0) {
            desiredChannels = (bitsPerPixel <= 8) ? 4 : channels;
        }

        ByteBuffer result = StbImage.convertChannels(getAllocator(),output, channels, width, height, desiredChannels, false);

        if (flipVertically) {
            result = StbImage.verticalFlip(getAllocator(),result, width, height, desiredChannels, false);
        }

        return new StbImageResult(result, width, height, desiredChannels, desiredChannels, false, false);
    }

    private boolean readSignature() {
        // Check for "BM" signature - compare bytes directly since buffer order may vary
        byte b0 = buffer.get(pos);
        byte b1 = buffer.get(pos + 1);
        if (b0 != (byte) 'B' || b1 != (byte) 'M') {
            return false;
        }
        pos += 2;
        return true;
    }

    private ByteBuffer decodeUncompressed(int rowStride) {
        ByteBuffer output = allocator.apply(width * height * channels);

        // BMP stores rows bottom-up, start from bottom (last row in file)
        for (int y = height - 1; y >= 0; y--) {
            int outOffset = y * width * channels;

            for (int x = 0; x < width; x++) {
                int pixelOffset = outOffset + x * channels;

                if (bitsPerPixel <= 8) {
                    int idx;
                    if (bitsPerPixel == 8) {
                        idx = readU8();
                    } else if (bitsPerPixel == 4) {
                        int b = readU8();
                        idx = (x % 2 == 0) ? (b >> 4) : (b & 0xF);
                    } else if (bitsPerPixel == 1) {
                        int b = readU8();
                        idx = (b >> (7 - (x % 8))) & 1;
                    } else {
                        idx = 0;
                    }

                    int p = idx * 4;
                    output.put(pixelOffset, palette[p + 2]);     // R
                    output.put(pixelOffset + 1, palette[p + 1]); // G
                    output.put(pixelOffset + 2, palette[p]);     // B
                    if (channels == 4) {
                        output.put(pixelOffset + 3, palette[p + 3]); // A
                    }
                } else if (bitsPerPixel == 16) {
                    int v = readU16LE();
                    int r = extractBits(v, maskR);
                    int g = extractBits(v, maskG);
                    int b = extractBits(v, maskB);
                    int a = maskA != 0 ? extractBits(v, maskA) : 255;
                    output.put(pixelOffset, (byte) r);
                    output.put(pixelOffset + 1, (byte) g);
                    output.put(pixelOffset + 2, (byte) b);
                    if (channels == 4) {
                        output.put(pixelOffset + 3, (byte) a);
                    }
                } else if (bitsPerPixel == 24) {
                    // Standard BMP uses BGR order, convert to RGB
                    byte b = (byte) readU8();
                    byte g = (byte) readU8();
                    byte r = (byte) readU8();
                    output.put(pixelOffset, r);     // R
                    output.put(pixelOffset + 1, g); // G
                    output.put(pixelOffset + 2, b); // B
                } else if (bitsPerPixel == 32) {
                    int v = readU32LE();
                    if (compression == BI_BITFIELDS || (maskR | maskG | maskB | maskA) != 0) {
                        int r = extractBits(v, maskR);
                        int g = extractBits(v, maskG);
                        int b = extractBits(v, maskB);
                        int a = maskA != 0 ? extractBits(v, maskA) : 255;
                        output.put(pixelOffset, (byte) r);
                        output.put(pixelOffset + 1, (byte) g);
                        output.put(pixelOffset + 2, (byte) b);
                        output.put(pixelOffset + 3, (byte) a);
                    } else {
                        // Standard BMP uses BGRA order, convert to RGBA
                        output.put(pixelOffset, (byte) ((v >> 16) & 0xFF));     // R
                        output.put(pixelOffset + 1, (byte) ((v >> 8) & 0xFF)); // G
                        output.put(pixelOffset + 2, (byte) (v & 0xFF)); // B
                        output.put(pixelOffset + 3, (byte) ((v >> 24) & 0xFF)); // A
                    }
                }
            }

            // Advance to row stride boundary.
            int rowBytes = (width * bitsPerPixel + 7) >> 3;
            pos += (rowStride - rowBytes);
        }

        // Set limit to actual data size since we use absolute positioning
        output.limit(width * height * channels);
        return output;
    }

    private ByteBuffer decodeRLE8() {
        ByteBuffer output = allocator.apply(width * height * channels);
        int x = 0, y = height - 1;

        while (y >= 0) {
            if (pos >= buffer.limit()) break;

            int b1 = readU8();
            int b2 = readU8();

            if (b1 == 0) {
                // Escape sequence
                if (b2 == 0) {
                    // End of line
                    x = 0;
                    y--;
                } else if (b2 == 1) {
                    // End of bitmap
                    break;
                } else if (b2 == 2) {
                    // Delta
                    int dx = readU8();
                    int dy = readU8();
                    x += dx;
                    y -= dy;
                } else {
                    // Absolute mode
                    for (int i = 0; i < b2; i++) {
                        int idx = readU8();
                        int p = idx * 4;
                        int outPos = (y * width + x) * channels;
                        output.put(outPos, palette[p + 2]);
                        output.put(outPos + 1, palette[p + 1]);
                        output.put(outPos + 2, palette[p]);
                        if (channels == 4) {
                            output.put(outPos + 3, palette[p + 3]);
                        }
                        x++;
                    }
                    // Pad to even byte
                    if ((b2 & 1) == 1) {
                        readU8();
                    }
                }
            } else {
                // Run mode
                int idx = b2;
                int p = idx * 4;
                for (int i = 0; i < b1 && x < width; i++) {
                    int outPos = (y * width + x) * channels;
                    output.put(outPos, palette[p + 2]);
                    output.put(outPos + 1, palette[p + 1]);
                    output.put(outPos + 2, palette[p]);
                    if (channels == 4) {
                        output.put(outPos + 3, palette[p + 3]);
                    }
                    x++;
                }
            }
        }

        // Set limit to actual data size since we use absolute positioning
        output.limit(width * height * channels);
        return output;
    }

    private ByteBuffer decodeRLE4() {
        ByteBuffer output = allocator.apply(width * height * channels);
        int x = 0, y = height - 1;

        while (y >= 0) {
            if (pos >= buffer.limit()) break;

            int b1 = readU8();
            int b2 = readU8();

            if (b1 == 0) {
                // Escape sequence
                if (b2 == 0) {
                    // End of line
                    x = 0;
                    y--;
                } else if (b2 == 1) {
                    // End of bitmap
                    break;
                } else if (b2 == 2) {
                    // Delta
                    int dx = readU8();
                    int dy = readU8();
                    x += dx;
                    y -= dy;
                } else {
                    // Absolute mode - b2 pixels, stored as 4-bit values
                    int count = b2;
                    int i = 0;
                    while (i < count) {
                        int byteVal = readU8();
                        int idx1 = (byteVal >> 4) & 0xF;
                        int p1 = idx1 * 4;
                        if (x < width && y >= 0) {
                            int outPos = (y * width + x) * channels;
                            output.put(outPos, palette[p1 + 2]);
                            output.put(outPos + 1, palette[p1 + 1]);
                            output.put(outPos + 2, palette[p1]);
                            if (channels == 4) {
                                output.put(outPos + 3, palette[p1 + 3]);
                            }
                        }
                        x++;
                        i++;

                        if (i < count) {
                            int idx2 = byteVal & 0xF;
                            int p2 = idx2 * 4;
                            if (x < width && y >= 0) {
                                int outPos = (y * width + x) * channels;
                                output.put(outPos, palette[p2 + 2]);
                                output.put(outPos + 1, palette[p2 + 1]);
                                output.put(outPos + 2, palette[p2]);
                                if (channels == 4) {
                                    output.put(outPos + 3, palette[p2 + 3]);
                                }
                            }
                            x++;
                            i++;
                        }
                    }
                    // Pad to even byte
                    if ((b2 & 3) == 1 || (b2 & 3) == 2) {
                        readU8();
                    }
                }
            } else {
                // Run mode
                int idx1 = (b2 >> 4) & 0xF;
                int idx2 = b2 & 0xF;
                int p1 = idx1 * 4;
                int p2 = idx2 * 4;

                for (int i = 0; i < b1 && x < width; i++) {
                    int idx = (i % 2 == 0) ? idx1 : idx2;
                    int p = (i % 2 == 0) ? p1 : p2;
                    int outPos = (y * width + x) * channels;
                    output.put(outPos, palette[p + 2]);
                    output.put(outPos + 1, palette[p + 1]);
                    output.put(outPos + 2, palette[p]);
                    if (channels == 4) {
                        output.put(outPos + 3, palette[p + 3]);
                    }
                    x++;
                }
            }
        }

        // Set limit to actual data size since we use absolute positioning
        output.limit(width * height * channels);
        return output;
    }

    private int readU8() {
        return buffer.get(pos++) & 0xFF;
    }

    private int readU16LE() {
        int val = Short.toUnsignedInt(buffer.getShort(pos));
        pos += 2;
        return val;
    }

    private int readS32LE() {
        int val = buffer.getInt(pos);
        pos += 4;
        return val;
    }

    private int readU32LE() {
        int val = buffer.getInt(pos);
        pos += 4;
        return val;
    }

    private int extractBits(int value, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int bits = Integer.bitCount(mask);
        int max = (1 << bits) - 1;
        int v = (value & mask) >>> shift;
        if (max == 0) return 0;
        return (v * 255 + (max >> 1)) / max;
    }
}
