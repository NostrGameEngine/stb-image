package com.stb.image;

import com.stb.image.allocator.StbAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF decoder (static), LZW decompression, palette to RGB conversion.
 */
public class GifDecoder {

    private static final String GIF87_SIGNATURE = "GIF87a";
    private static final String GIF89_SIGNATURE = "GIF89a";

    // GIF extension codes
    private static final int EXT_GRAPHICS_CONTROL = 0xF9;
    private static final int EXT_APPLICATION = 0xFF;
    private static final int EXT_COMMENT = 0xFE;
    private static final int EXT_PLAIN_TEXT = 0x01;
    private static final int EXT_IMAGE = 0x2C;

    private ByteBuffer buffer;
    private int pos;
    private StbAllocator allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int globalColorTableSize;
    private byte[] globalColorTable;
    private int backgroundColorIndex;
    private int pixelAspectRatio;

    private boolean hasGlobalColorTable;
    private boolean hasTransparency;
    private int transparentColorIndex = -1;
    private int disposalMethod;

    public static StbImageInfo getInfo(ByteBuffer buffer) {
        return getInfo(buffer, StbAllocator.DEFAULT);
    }

    public static StbImageInfo getInfo(ByteBuffer buffer, StbAllocator allocator) {
        GifDecoder decoder = new GifDecoder(buffer.duplicate(), allocator, false);
        return decoder.decodeInfo();
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels) {
        return decode(buffer, desiredChannels, StbAllocator.DEFAULT, StbImage.isFlipVertically());
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels, StbAllocator allocator) {
        return decode(buffer, desiredChannels, allocator, StbImage.isFlipVertically());
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels, StbAllocator allocator, boolean flipVertically) {
        GifDecoder decoder = new GifDecoder(buffer, allocator, flipVertically);
        return decoder.decode(desiredChannels);
    }

    public GifDecoder(ByteBuffer buffer, StbAllocator allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate();
        this.allocator = allocator != null ? allocator : StbAllocator.DEFAULT;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    private StbImageInfo decodeInfo() {
        try {
            // Read signature
            String signature = readString(6);
            if (!signature.equals(GIF87_SIGNATURE) && !signature.equals(GIF89_SIGNATURE)) {
                return null;
            }

            // Logical screen descriptor
            width = readU16LE();
            height = readU16LE();

            int packed = readU8();
            hasGlobalColorTable = (packed & 0x80) != 0;
            globalColorTableSize = 1 << ((packed & 0x7) + 1);
            backgroundColorIndex = readU8();
            pixelAspectRatio = readU8();

            return new StbImageInfo(width, height, 3, false, StbImageInfo.ImageFormat.GIF);
        } catch (Exception e) {
            return null;
        }
    }

    private StbImageResult decode(int desiredChannels) {
        // Read signature
        String signature = readString(6);
        if (!signature.equals(GIF87_SIGNATURE) && !signature.equals(GIF89_SIGNATURE)) {
            throw new StbFailureException("Not a GIF file");
        }

        // Logical screen descriptor
        width = readU16LE();
        height = readU16LE();

        int packed = readU8();
        hasGlobalColorTable = (packed & 0x80) != 0;
        globalColorTableSize = 1 << ((packed & 0x7) + 1);
        backgroundColorIndex = readU8();
        pixelAspectRatio = readU8();

        StbImage.validateDimensions(width, height);

        // Read global color table
        if (hasGlobalColorTable) {
            globalColorTable = new byte[globalColorTableSize * 3];
            for (int i = 0; i < globalColorTableSize * 3; i++) {
                globalColorTable[i] = (byte) readU8();
            }
        } else {
            globalColorTableSize = 0;
            globalColorTable = new byte[0];
        }

        // Process blocks
        boolean foundImage = false;
        byte[] imageData = null;
        int localColorTableSize = 0;
        byte[] localColorTable = null;

        while (pos < buffer.limit() - 1) {
            int blockType = readU8();

            if (blockType == EXT_GRAPHICS_CONTROL) {
                readGraphicsControlBlock();
            } else if (blockType == EXT_IMAGE) {
                imageData = readImageBlock();
                foundImage = true;
                break;
            } else if (blockType == 0x00) {
                // Block terminator or extension
                if (pos < buffer.limit() - 1) {
                    int next = buffer.get(pos);
                    if (next == EXT_IMAGE) {
                        // Image block follows
                    } else {
                        pos--;
                    }
                }
            } else if (blockType == EXT_APPLICATION ||
                       blockType == EXT_COMMENT ||
                       blockType == EXT_PLAIN_TEXT) {
                // Skip extension
                skipExtension(blockType);
            } else if (blockType == 0x3B) {
                // GIF trailer
                break;
            } else {
                // Skip unknown block
                if (blockType > 0x80) {
                    // Skip sub-blocks
                }
            }
        }

        if (!foundImage) {
            throw new StbFailureException("No image data found");
        }

        // Decode LZW data
        if (imageData == null || imageData.length == 0) {
            throw new StbFailureException("No image data");
        }

        // Determine which color table to use
        byte[] colorTable = localColorTable != null ? localColorTable : globalColorTable;
        if (colorTable == null || colorTable.length == 0) {
            colorTable = new byte[256 * 3]; // Default grayscale
        }

        // Decode
        ByteBuffer output = lzwDecode(imageData, width, height, colorTable);

        // Convert to desired channels
        int srcChannels = 4;
        int outChannels = (desiredChannels == 0) ? 3 : desiredChannels;

        ByteBuffer result = StbImage.convertChannels(output, srcChannels, width, height, outChannels, false);

        if (flipVertically) {
            result = StbImage.verticalFlip(result, width, height, outChannels, false);
        }

        return new StbImageResult(result, width, height, outChannels, desiredChannels, false, false);
    }

    private void readGraphicsControlBlock() {
        int blockSize = readU8();
        int packed = readU8();
        int delayTime = readU16LE();
        int transparentColorIndex = readU8();
        int blockTerminator = readU8();

        disposalMethod = (packed >> 2) & 0x7;
        hasTransparency = (packed & 0x1) != 0;
        if (hasTransparency) {
            this.transparentColorIndex = transparentColorIndex;
        }
    }

    private byte[] readImageBlock() {
        // Image descriptor
        int left = readU16LE();
        int top = readU16LE();
        int imgWidth = readU16LE();
        int imgHeight = readU16LE();

        int packed = readU8();
        boolean hasLocalColorTable = (packed & 0x80) != 0;
        int localColorTableSize = 1 << ((packed & 0x7) + 1);

        byte[] localColorTable = null;
        if (hasLocalColorTable) {
            localColorTable = new byte[localColorTableSize * 3];
            for (int i = 0; i < localColorTableSize * 3; i++) {
                localColorTable[i] = (byte) readU8();
            }
        }

        // LZW minimum code size
        int lzwMinCodeSize = readU8();

        // Read all sub-blocks
        byte[] lzwData = new byte[0];
        while (true) {
            int blockSize = readU8();
            if (blockSize == 0) {
                break;
            }
            byte[] newData = new byte[lzwData.length + blockSize];
            System.arraycopy(lzwData, 0, newData, 0, lzwData.length);
            buffer.get(pos, newData, lzwData.length, blockSize);
            pos += blockSize;
            lzwData = newData;
        }

        return lzwData;
    }

    private void skipExtension(int extensionType) {
        while (true) {
            int blockSize = readU8();
            if (blockSize == 0) {
                break;
            }
            pos += blockSize;
        }
    }

    private ByteBuffer lzwDecode(byte[] lzwData, int width, int height, byte[] colorTable) {
        ByteBuffer output = allocator.allocate(width * height * 4);

        // Create sub-buffer for LZW decoding
        ByteBuffer data = ByteBuffer.wrap(lzwData);

        int lzwMinCodeSize = data.get() & 0xFF;
        int clearCode = 1 << lzwMinCodeSize;
        int eoiCode = clearCode + 1;
        int codeSize = lzwMinCodeSize + 1;
        int nextCode = eoiCode + 1;
        int maxCode = (1 << codeSize) - 1;

        // Dictionary
        List<int[]> dictionary = new ArrayList<>();
        for (int i = 0; i < clearCode; i++) {
            dictionary.add(new int[]{i});
        }
        dictionary.add(new int[]{-1}); // clear code placeholder
        dictionary.add(new int[]{-2}); // eoi code placeholder

        int[] prefix = new int[4096];
        int[] suffix = new int[4096];

        int bits = 0;
        int bitCount = 0;
        int dataIndex = 0;
        int outputIndex = 0;

        int code = 0;
        int prevCode = -1;

        while (outputIndex < width * height) {
            // Read code
            while (bitCount < codeSize && dataIndex < lzwData.length) {
                bits |= (lzwData[dataIndex++] & 0xFF) << bitCount;
                bitCount += 8;
            }

            if (bitCount < codeSize) {
                break;
            }

            code = bits & maxCode;
            bits >>= codeSize;
            bitCount -= codeSize;

            if (code == eoiCode) {
                break;
            }

            if (code == clearCode) {
                codeSize = lzwMinCodeSize + 1;
                nextCode = eoiCode + 1;
                maxCode = (1 << codeSize) - 1;
                prevCode = -1;
                continue;
            }

            int[] sequence;

            if (code < nextCode) {
                // Existing code
                sequence = dictionary.get(code);
            } else if (code == nextCode && prevCode != -1) {
                // New code - extend previous
                int[] prev = dictionary.get(prevCode);
                sequence = new int[prev.length + 1];
                System.arraycopy(prev, 0, sequence, 0, prev.length);
                sequence[prev.length] = prev[0];
            } else {
                // Error
                break;
            }

            // Output sequence
            for (int i = 0; i < sequence.length && outputIndex < width * height; i++) {
                int idx = sequence[i];
                int p = idx * 3;
                byte r = (p < colorTable.length) ? colorTable[p] : 0;
                byte g = (p + 1 < colorTable.length) ? colorTable[p + 1] : 0;
                byte b = (p + 2 < colorTable.length) ? colorTable[p + 2] : 0;
                byte a = (byte) (hasTransparency && idx == transparentColorIndex ? 0 : 0xFF);

                output.put(outputIndex * 4, r);
                output.put(outputIndex * 4 + 1, g);
                output.put(outputIndex * 4 + 2, b);
                output.put(outputIndex * 4 + 3, a);
                outputIndex++;
            }

            // Add to dictionary
            if (prevCode != -1 && nextCode < 4096) {
                int[] prev = dictionary.get(prevCode);
                int[] newEntry = new int[prev.length + 1];
                System.arraycopy(prev, 0, newEntry, 0, prev.length);
                newEntry[prev.length] = sequence[0];

                if (dictionary.size() <= nextCode) {
                    dictionary.add(newEntry);
                } else {
                    dictionary.set(nextCode, newEntry);
                }

                nextCode++;
                if (nextCode > maxCode && codeSize < 12) {
                    codeSize++;
                    maxCode = (1 << codeSize) - 1;
                }
            }

            prevCode = code;
        }

        // Set limit to actual data size since we use absolute positioning
        output.limit(width * height * 4);
        return output;
    }

    private String readString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) (buffer.get(pos++) & 0xFF));
        }
        return sb.toString();
    }

    private int readU8() {
        return buffer.get(pos++) & 0xFF;
    }

    private int readU16LE() {
        int b0 = buffer.get(pos++) & 0xFF;
        int b1 = buffer.get(pos++) & 0xFF;
        return b0 | (b1 << 8);
    }
}
