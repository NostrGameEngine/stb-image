package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;


/**
 * PSD decoder for composite image, 8/16-bit.
 */
public class PsdDecoder implements StbDecoder{

    // PSD signature "8BPS"
    private static final int PSD_SIGNATURE = 0x38425053;

    // Color modes
    private static final int MODE_BITMAP = 0;
    private static final int MODE_GRAYSCALE = 1;
    private static final int MODE_INDEXED = 2;
    private static final int MODE_RGB = 3;
    private static final int MODE_CMYK = 4;
    private static final int MODE_MULTICHANNEL = 7;
    private static final int MODE_DUOTONE = 8;
    private static final int MODE_LAB = 9;

    private ByteBuffer buffer;
    private int pos;
    private IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int channels;
    private int bitsPerChannel;
    private int colorMode;
    private int colorDepth;


    public static boolean isPsd(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;
        buffer=buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        int sig = buffer.getInt();
        int version = buffer.getShort() & 0xFFFF;
        return sig == 0x38425053 && (version == 1 || version == 2);
    }

 
    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }
    
    public PsdDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    @Override
    public StbImageInfo info() {
        try {
            // Read signature
            if (readU32BE() != PSD_SIGNATURE) {
                return null;
            }

            // Skip version
            readU16BE();

            // Skip reserved
            pos += 6;

            // Read channels, height, width
            int numChannels = readU16BE();
            int h = readU32BE();
            int w = readU32BE();

            // Bits per channel
            int bpc = readU16BE();

            // Color mode
            int cm = readU16BE();

            int infoChannels = (numChannels >= 3) ? 3 : 1;

            return new StbImageInfo(w, h, infoChannels, bpc == 16, StbImageInfo.ImageFormat.PSD);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public StbImageResult load(int desiredChannels) {
        // Read signature
        if (readU32BE() != PSD_SIGNATURE) {
            throw new StbFailureException("Not a PSD file");
        }

        // Read version
        int version = readU16BE();
        if (version != 1 && version != 2) {
            throw new StbFailureException("Unsupported PSD version: " + version);
        }

        // Skip reserved
        pos += 6;

        // Read channels, height, width
        channels = readU16BE();
        height = readU32BE();
        width = readU32BE();

        StbImage.validateDimensions(width, height);

        // Bits per channel
        bitsPerChannel = readU16BE();

        // Color mode
        colorMode = readU16BE();

        // Skip color mode data
        int colorModeDataLength = readU32BE();
        pos += colorModeDataLength;

        // Skip layer/mask info (not supported for composite)
        int layerMaskDataLength = readU32BE();
        pos += layerMaskDataLength;

        // Determine channels to read
        int outChannels;
        if (colorMode == MODE_RGB) {
            outChannels = Math.min(channels, 3);
        } else if (colorMode == MODE_GRAYSCALE) {
            outChannels = 1;
        } else if (colorMode == MODE_CMYK) {
            outChannels = 4;
        } else {
            outChannels = Math.min(channels, 3);
        }

        if (desiredChannels != 0) {
            outChannels = Math.min(outChannels, desiredChannels);
        }

        // Determine bytes per channel
        boolean is16Bit = (bitsPerChannel == 16);
        int bytesPerChannel = is16Bit ? 2 : 1;

        // Allocate output buffer
        ByteBuffer output = allocator.apply(width * height * outChannels * bytesPerChannel);

        // Read image data
        // Compression: 0 = raw, 1 = RLE
        int compression = readU16BE();

        if (compression == 0) {
            decodeUncompressed(output, outChannels, bytesPerChannel);
        } else if (compression == 1) {
            decodeRLE(output, outChannels, bytesPerChannel);
        } else {
            throw new StbFailureException("Unsupported compression: " + compression);
        }

        // Set limit to actual data size since we use absolute positioning
        output.limit(width * height * outChannels * bytesPerChannel);

        if (flipVertically) {
            output = StbImage.verticalFlip(getAllocator(),output, width, height, outChannels, is16Bit);
        }

        return new StbImageResult(output, width, height, outChannels, desiredChannels, is16Bit, false);
    }

    private void decodeUncompressed(ByteBuffer output, int outChannels, int bytesPerChannel) {
        for (int c = 0; c < outChannels; c++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pos = (y * width + x) * outChannels * bytesPerChannel + c * bytesPerChannel;
                    if (bytesPerChannel == 2) {
                        output.putShort(pos, (short) readU16BE());
                    } else {
                        output.put(pos, (byte) readU8());
                    }
                }
            }
        }
    }

    private void decodeRLE(ByteBuffer output, int outChannels, int bytesPerChannel) {
        // RLE compressed - read run lengths for each row of each channel
        for (int c = 0; c < outChannels; c++) {
            for (int y = 0; y < height; y++) {
                // Read run count for this row
                int runCount = readU16BE();

                int x = 0;
                while (x < width && pos < buffer.limit() - 1) {
                    int header = readU8();
                    if (header >= 128) {
                        // Run of repeated values
                        int count = 256 - header + 1;
                        int value;
                        if (bytesPerChannel == 2) {
                            value = readU16BE();
                        } else {
                            value = readU8();
                        }

                        for (int i = 0; i < count && x < width; i++) {
                            int outPos = (y * width + x) * outChannels * bytesPerChannel + c * bytesPerChannel;
                            if (bytesPerChannel == 2) {
                                output.putShort(outPos, (short) value);
                            } else {
                                output.put(outPos, (byte) value);
                            }
                            x++;
                        }
                    } else {
                        // Raw values
                        int count = header + 1;
                        for (int i = 0; i < count && x < width; i++) {
                            int value;
                            if (bytesPerChannel == 2) {
                                value = readU16BE();
                            } else {
                                value = readU8();
                            }

                            int outPos = (y * width + x) * outChannels * bytesPerChannel + c * bytesPerChannel;
                            if (bytesPerChannel == 2) {
                                output.putShort(outPos, (short) value);
                            } else {
                                output.put(outPos, (byte) value);
                            }
                            x++;
                        }
                    }
                }
            }
        }
    }

    private int readU8() {
        return buffer.get(pos++) & 0xFF;
    }

    private int readU16BE() {
        int b0 = buffer.get(pos++) & 0xFF;
        int b1 = buffer.get(pos++) & 0xFF;
        return (b0 << 8) | b1;
    }

    private int readU32BE() {
        int b0 = buffer.get(pos++) & 0xFF;
        int b1 = buffer.get(pos++) & 0xFF;
        int b2 = buffer.get(pos++) & 0xFF;
        int b3 = buffer.get(pos++) & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}
