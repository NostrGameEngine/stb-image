package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/**
 * PSD decoder for composited RGB view, 8/16-bit.
 */
public class PsdDecoder implements StbDecoder {

    private static final int PSD_SIGNATURE = 0x38425053;
    private static final int MODE_RGB = 3;

    private final ByteBuffer buffer;
    private int pos;
    private final IntFunction<ByteBuffer> allocator;
    private final boolean flipVertically;

    private int width;
    private int height;
    private int channels;
    private int bitsPerChannel;
    private int colorMode;

    public static boolean isPsd(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return false;
        ByteBuffer probe = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        int sig = probe.getInt();
        int version = probe.getShort() & 0xFFFF;
        return sig == PSD_SIGNATURE && (version == 1 || version == 2);
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
        int saved = pos;
        try {
            pos = 0;
            parseHeader();
            return new StbImageInfo(width, height, 4, bitsPerChannel == 16, StbImageInfo.ImageFormat.PSD);
        } catch (RuntimeException e) {
            return null;
        } finally {
            pos = saved;
        }
    }

    @Override
    public StbImageResult load(int desiredChannels) {
        pos = 0;
        parseHeader();

        StbImage.validateDimensions(width, height);

        boolean is16Bit = bitsPerChannel == 16;
        int bytesPerChannel = is16Bit ? 2 : 1;
        int srcChannels = Math.min(channels, 4);

        // stb PSD path decodes RGBA and then converts for req_comp.
        ByteBuffer rgba = allocator.apply(width * height * 4 * bytesPerChannel);
        fillOpaqueAlpha(rgba, is16Bit);

        int compression = readU16BE();
        if (compression == 0) {
            decodeRaw(rgba, srcChannels, bytesPerChannel);
        } else if (compression == 1) {
            decodeRle(rgba, srcChannels, bytesPerChannel);
        } else {
            throw new StbFailureException("Unsupported PSD compression: " + compression);
        }

        rgba.limit(width * height * 4 * bytesPerChannel);
        rgba.position(0);

        int outChannels = (desiredChannels == 0) ? 4 : desiredChannels;
        ByteBuffer out = StbImage.convertChannels(getAllocator(), rgba, 4, width, height, outChannels, is16Bit);
        if (flipVertically) {
            out = StbImage.verticalFlip(getAllocator(), out, width, height, outChannels, is16Bit);
        }

        return new StbImageResult(out, width, height, outChannels, desiredChannels, is16Bit, false);
    }

    private void parseHeader() {
        if (readU32BE() != PSD_SIGNATURE) {
            throw new StbFailureException("Not a PSD file");
        }

        int version = readU16BE();
        if (version != 1 && version != 2) {
            throw new StbFailureException("Unsupported PSD version: " + version);
        }

        pos += 6; // reserved

        channels = readU16BE();
        if (channels < 0 || channels > 16) {
            throw new StbFailureException("Invalid PSD channel count");
        }
        height = readU32BE();
        width = readU32BE();

        bitsPerChannel = readU16BE();
        if (bitsPerChannel != 8 && bitsPerChannel != 16) {
            throw new StbFailureException("Unsupported PSD bit depth: " + bitsPerChannel);
        }

        colorMode = readU16BE();
        if (colorMode != MODE_RGB) {
            throw new StbFailureException("Unsupported PSD color mode");
        }

        int colorModeDataLength = readU32BE();
        pos += colorModeDataLength;

        int imageResourcesLength = readU32BE();
        pos += imageResourcesLength;

        int layerMaskDataLength = readU32BE();
        pos += layerMaskDataLength;

        if (pos > buffer.limit()) {
            throw new StbFailureException("Corrupt PSD header");
        }
    }

    private void fillOpaqueAlpha(ByteBuffer out, boolean is16Bit) {
        int pixels = width * height;
        if (is16Bit) {
            for (int i = 0; i < pixels; i++) {
                out.putShort((i * 8) + 6, (short) 0xFFFF);
            }
        } else {
            for (int i = 0; i < pixels; i++) {
                out.put((i * 4) + 3, (byte) 0xFF);
            }
        }
    }

    private void decodeRaw(ByteBuffer out, int srcChannels, int bytesPerChannel) {
        for (int c = 0; c < srcChannels; c++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int dst = ((y * width + x) * 4 + c) * bytesPerChannel;
                    if (bytesPerChannel == 2) {
                        out.putShort(dst, (short) readU16BE());
                    } else {
                        out.put(dst, (byte) readU8());
                    }
                }
            }
        }
        // skip any extra channels we don't output
        int extra = channels - srcChannels;
        if (extra > 0) {
            long skip = (long) extra * width * height * bytesPerChannel;
            pos += (int) skip;
        }
    }

    private void decodeRle(ByteBuffer out, int srcChannels, int bytesPerChannel) {
        int rows = channels * height;
        int[] rowLen = new int[rows];
        for (int i = 0; i < rows; i++) {
            rowLen[i] = readU16BE();
        }

        for (int c = 0; c < channels; c++) {
            for (int y = 0; y < height; y++) {
                int n = rowLen[c * height + y];
                int rowEnd = pos + n;

                if (c < srcChannels) {
                    int x = 0;
                    while (x < width && pos < rowEnd) {
                        int header = readS8();
                        if (header >= 0) {
                            int count = header + 1;
                            for (int i = 0; i < count && x < width && pos < rowEnd; i++) {
                                int dst = ((y * width + x) * 4 + c) * bytesPerChannel;
                                if (bytesPerChannel == 2) {
                                    out.putShort(dst, (short) readU16BE());
                                } else {
                                    out.put(dst, (byte) readU8());
                                }
                                x++;
                            }
                        } else if (header != -128) {
                            int count = 1 - header;
                            if (bytesPerChannel == 2) {
                                int v = readU16BE();
                                for (int i = 0; i < count && x < width; i++) {
                                    int dst = ((y * width + x) * 4 + c) * 2;
                                    out.putShort(dst, (short) v);
                                    x++;
                                }
                            } else {
                                int v = readU8();
                                for (int i = 0; i < count && x < width; i++) {
                                    int dst = ((y * width + x) * 4 + c);
                                    out.put(dst, (byte) v);
                                    x++;
                                }
                            }
                        }
                    }
                }

                pos = rowEnd;
                if (pos > buffer.limit()) {
                    throw new StbFailureException("Corrupt PSD RLE data");
                }
            }
        }
    }

    private int readU8() {
        return buffer.get(pos++) & 0xFF;
    }

    private int readS8() {
        return buffer.get(pos++);
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
