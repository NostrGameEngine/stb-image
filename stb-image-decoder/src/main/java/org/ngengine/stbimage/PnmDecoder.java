package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;


/**
 * PNM decoder for PPM/PGM (binary), including 16-bit.
 */
public class PnmDecoder implements StbDecoder {

    // PNM magic numbers
    private static final int PBM_ASCII = 'P' + ('1' << 8);
    private static final int PGM_ASCII = 'P' + ('2' << 8);
    private static final int PPM_ASCII = 'P' + ('3' << 8);
    private static final int PBM_BINARY = 'P' + ('4' << 8);
    private static final int PGM_BINARY = 'P' + ('5' << 8);
    private static final int PPM_BINARY = 'P' + ('6' << 8);
    private static final int PFM = 'P' + ('f' << 8);

    private ByteBuffer buffer;
    private int pos;
    private IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int maxValue;
    private int format;
    private int channels;
    private boolean is16Bit;
    private boolean isFloat;

    /**
     * Tests whether the source starts with a supported PNM magic value.
     *
     * @param buffer source bytes
     * @return true when signature is a supported PNM variant
     */
    public static boolean isPnm(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return false;
        buffer=buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        return b0 == 'P' && (b1 >= '1' && b1 <= '9') && b1 != '8' && b1 != '9';
    }
 
    /**
     * Creates a PNM decoder instance.
     *
     * @param buffer source data
     * @param allocator output allocator
     * @param flipVertically true to vertically flip decoded output
     */
    public PnmDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
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
    public StbImageInfo info() {
        try {
            buffer.position(0);
            pos = 0;
            readHeader();
            return new StbImageInfo(width, height, channels, is16Bit || isFloat, StbImageInfo.ImageFormat.PNM);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        // Rewind buffer to start before reading header
        buffer.position(0);
        pos = 0;  // Also reset our position tracker
        readHeader();

        StbImage.validateDimensions(width, height);

        int outChannels = (desiredChannels == 0) ? channels : desiredChannels;
        int bytesPerChannel = (is16Bit || isFloat) ? 2 : 1;
        if (isFloat) bytesPerChannel = 4;

        ByteBuffer output = allocator.apply(width * height * outChannels * bytesPerChannel);

        // Decode based on format
        if (format == PGM_BINARY) {
            decodeBinaryGray(output, outChannels, bytesPerChannel);
        } else if (format == PPM_BINARY) {
            decodeBinaryColor(output, outChannels, bytesPerChannel);
        } else if (format == PFM) {
            decodePFM(output);
        } else {
            throw new StbFailureException("Unsupported PNM format: " + format);
        }

        // Set limit since absolute positioning doesn't advance position
        int totalBytes = width * height * outChannels * bytesPerChannel;
        output.limit(totalBytes);
        output.position(0);

        if (flipVertically) {
            output = StbImage.verticalFlip(getAllocator(), output, width, height, outChannels, bytesPerChannel);
        }

        return new StbImageResult(output, width, height, outChannels, desiredChannels, is16Bit, isFloat);
    }

    private void readHeader() {
        // Skip comments and whitespace
        skipWhitespaceAndComments();

        // Read magic number (e.g., "P5" -> 'P' + ('5' << 8))
        int first = readNonWhitespace();
        int second = readNonWhitespace();
        int magic = first | (second << 8);

        // Check for PFM
        if (magic == PFM) {
            format = PFM;
            isFloat = true;
            // PFM is always color (RGB)
            channels = 3;
        } else {
            format = magic;
            isFloat = false;
        }

        // Skip whitespace
        skipWhitespaceAndComments();

        // Read width
        width = readInt();

        // Skip whitespace
        skipWhitespaceAndComments();

        // Read height
        height = readInt();

        // Skip whitespace
        skipWhitespaceAndComments();

        // Read max value (not for PBM)
        if (format != PBM_BINARY && format != PBM_ASCII) {
            maxValue = readInt();
            is16Bit = (maxValue > 255);
        } else {
            maxValue = 1;
        }

        // Skip whitespace after max value before binary data
        skipWhitespaceAndComments();

        // Determine channels
        switch (format) {
            case PGM_BINARY:
            case PGM_ASCII:
                channels = 1;
                break;
            case PPM_BINARY:
            case PPM_ASCII:
                channels = 3;
                break;
            case PFM:
                channels = 3;
                break;
            default:
                channels = 1;
        }
    }

    private void decodeBinaryGray(ByteBuffer output, int outChannels, int bytesPerChannel) {
        if (bytesPerChannel == 2) {
            // 16-bit grayscale
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int val = readU16BE();
                    // Scale to 16-bit range
                    if (maxValue < 65535) {
                        val = (val * 65535) / maxValue;
                    }
                    int outPos = (y * width + x) * outChannels * 2;
                    if (outChannels == 1) {
                        output.putShort(outPos, (short) val);
                    } else if (outChannels == 2) {
                        output.putShort(outPos, (short) val);
                        output.putShort(outPos + 2, (short) 65535);
                    } else if (outChannels == 3) {
                        output.putShort(outPos, (short) val);
                        output.putShort(outPos + 2, (short) val);
                        output.putShort(outPos + 4, (short) val);
                    } else {
                        output.putShort(outPos, (short) val);
                        output.putShort(outPos + 2, (short) val);
                        output.putShort(outPos + 4, (short) val);
                        output.putShort(outPos + 6, (short) 65535);
                    }
                }
            }
        } else {
            // 8-bit grayscale
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int val = readU8();
                    if (maxValue < 255) {
                        val = (val * 255) / maxValue;
                    }
                    int outPos = (y * width + x) * outChannels;
                    if (outChannels == 1) {
                        output.put(outPos, (byte) val);
                    } else if (outChannels == 2) {
                        output.put(outPos, (byte) val);
                        output.put(outPos + 1, (byte) 255);
                    } else if (outChannels == 3) {
                        output.put(outPos, (byte) val);
                        output.put(outPos + 1, (byte) val);
                        output.put(outPos + 2, (byte) val);
                    } else {
                        output.put(outPos, (byte) val);
                        output.put(outPos + 1, (byte) val);
                        output.put(outPos + 2, (byte) val);
                        output.put(outPos + 3, (byte) 255);
                    }
                }
            }
        }
    }

    private void decodeBinaryColor(ByteBuffer output, int outChannels, int bytesPerChannel) {
        if (bytesPerChannel == 2) {
            // 16-bit RGB
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = readU16BE();
                    int g = readU16BE();
                    int b = readU16BE();

                    // Scale to 16-bit range
                    if (maxValue < 65535) {
                        r = (r * 65535) / maxValue;
                        g = (g * 65535) / maxValue;
                        b = (b * 65535) / maxValue;
                    }

                    int outPos = (y * width + x) * outChannels * 2;
                    output.putShort(outPos, (short) r);
                    output.putShort(outPos + 2, (short) g);
                    if (outChannels == 4) {
                        output.putShort(outPos + 4, (short) b);
                        output.putShort(outPos + 6, (short) 65535);
                    } else {
                        output.putShort(outPos + 4, (short) b);
                    }
                }
            }
        } else {
            // 8-bit RGB
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = readU8();
                    int g = readU8();
                    int b = readU8();

                    // Scale to 8-bit range
                    if (maxValue < 255) {
                        r = (r * 255) / maxValue;
                        g = (g * 255) / maxValue;
                        b = (b * 255) / maxValue;
                    }

                    int outPos = (y * width + x) * outChannels;
                    output.put(outPos, (byte) r);
                    output.put(outPos + 1, (byte) g);
                    if (outChannels == 4) {
                        output.put(outPos + 2, (byte) b);
                        output.put(outPos + 3, (byte) 255);
                    } else {
                        output.put(outPos + 2, (byte) b);
                    }
                }
            }
        }
    }

    private void decodePFM(ByteBuffer output) {
        // PFM uses big-endian floats
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = readFloat();
                int g = readFloat();
                int b = readFloat();

                int outPos = (y * width + x) * 12;
                output.putFloat(outPos, Float.intBitsToFloat(r));
                output.putFloat(outPos + 4, Float.intBitsToFloat(g));
                output.putFloat(outPos + 8, Float.intBitsToFloat(b));
            }
        }
    }

    private int readFloat() {
        // Read as IEEE 754 big-endian float
        int bits = 0;
        for (int i = 3; i >= 0; i--) {
            bits = (bits << 8) | (readU8() & 0xFF);
        }
        return bits;
    }

    private void skipWhitespaceAndComments() {
        while (pos < buffer.limit()) {
            int b = buffer.get(pos) & 0xFF;
            // Check for common whitespace: space(32), tab(9), newline(10), carriage return(13)
            if (b == 32 || b == 9 || b == 10 || b == 13) {
                pos++;
            } else if (b == '#') {
                // Skip comment
                while (pos < buffer.limit()) {
                    int cb = buffer.get(pos++) & 0xFF;
                    if (cb == '\n' || cb == '\r') break;
                }
            } else {
                break;
            }
        }
    }

    private char readNonWhitespace() {
        skipWhitespaceAndComments();
        return (char) (buffer.get(pos++) & 0xFF);
    }

    private int readInt() {
        skipWhitespaceAndComments();
        int sign = 1;
        char c = (char) (buffer.get(pos) & 0xFF);
        if (c == '-') {
            sign = -1;
            pos++;
        } else if (c == '+') {
            pos++;
        }

        int value = 0;
        while (pos < buffer.limit()) {
            c = (char) (buffer.get(pos) & 0xFF);
            if (!Character.isDigit(c)) {
                break;
            }
            value = value * 10 + (c - '0');
            pos++;
        }
        return value * sign;
    }

    private int readU8() {
        return buffer.get(pos++) & 0xFF;
    }

    private int readU16BE() {
        int b0 = buffer.get(pos++) & 0xFF;
        int b1 = buffer.get(pos++) & 0xFF;
        return (b0 << 8) | b1;
    }
}
