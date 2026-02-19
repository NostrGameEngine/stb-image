package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;


/**
 * Radiance HDR decoder, RGBE to RGB float conversion.
 */
public class HdrDecoder implements StbDecoder {

    private static final String HDR_SIGNATURE = "#?RADIANCE";
    private static final String HDR_SIGNATURE_ALT = "#?RGBE";

    private ByteBuffer buffer;
    private int pos;
    private IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;


    public static boolean isHdr(ByteBuffer buffer) {
        if (buffer.remaining() < 11) return false;
        buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        // Skip whitespace
        while (buffer.hasRemaining()) {
            char c = (char) (buffer.get() & 0xFF);
            if (!Character.isWhitespace(c)) {
                buffer.position(buffer.position() - 1);
                break;
            }
        }
        // Check for #?RADIANCE or #?RGBE
        byte[] sig = new byte[10];
        buffer.get(sig);

        return (sig[0] == '#' && sig[1] == '?') &&
                ((sig[2] == 'R' && sig[3] == 'A' && sig[4] == 'D' && sig[5] == 'I') ||
                (sig[2] == 'R' && sig[3] == 'G' && sig[4] == 'B' && sig[5] == 'E'));
       
    }


     
    public HdrDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    @Override
    public StbImageInfo info() {
        try {
            // Skip any initial whitespace
            skipWhitespace();

            String signature = readLine();
            if (signature == null || !signature.startsWith("#?")) {
                return null;
            }
            if (!signature.contains("RADIANCE") && !signature.contains("RGBE")) {
                return null;
            }

            // Skip remaining header lines
            String line;
            while (true) {
                line = readLine();
                if (line == null) {
                    return null;
                }
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }
                // Resolution line
                if (line.contains("+X") || line.contains("-X")) {
                    break;
                }
            }

            // Read resolution
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4) {
                return null;
            }

            // Format is: -Y height +X width or -X width +Y height
            int w = 0, h = 0;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("+X") || parts[i].equals("-X")) {
                    w = Integer.parseInt(parts[i + 1]);
                } else if (parts[i].equals("+Y") || parts[i].equals("-Y")) {
                    h = Integer.parseInt(parts[i + 1]);
                }
            }

            if (w <= 0 || h <= 0) {
                return null;
            }

            return new StbImageInfo(w, h, 3, false, StbImageInfo.ImageFormat.HDR);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }
    @Override
    public StbImageResult load(int desiredChannels) {
        // Read header
        readHeader();

        StbImage.validateDimensions(width, height);

        // Allocate output buffer (3 channels float = 4 bytes each)
        int outChannels = (desiredChannels == 0 || desiredChannels == 4) ? 3 : desiredChannels;
        ByteBuffer output = allocator.apply(width * height * outChannels * 4);

        // Check for new RLE format
        boolean isRle = false;
        String line = readLine();
        if (line != null && line.contains("=")) {
            // RLE format
            isRle = true;
        } else if (line != null) {
            // Put back
            pos -= line.length() + 1;
        }

        if (isRle) {
            // Skip format spec
            while (true) {
                line = readLine();
                if (line == null || line.length() == 0) {
                    break;
                }
                if (line.startsWith("-Y") || line.startsWith("+Y")) {
                    break;
                }
            }
        }

        // Decode based on format
        if (isRle) {
            decodeRLE(output, outChannels);
        } else {
            decodeOldFormat(output, outChannels);
        }

        // Set position to the actual amount of data written
        output.position(width * height * outChannels * 4);
        output.flip();

        if (flipVertically) {
            output = StbImage.verticalFlip(getAllocator(),output, width, height, outChannels, true);
        }

        return new StbImageResult(output, width, height, outChannels, desiredChannels, false, true);
    }

    private void readHeader() {
        // Skip any initial whitespace
        skipWhitespace();

        // Check signature
        String firstLine = readLine();
        if (firstLine == null || !firstLine.startsWith("#?")) {
            throw new StbFailureException("Invalid HDR file");
        }

        // Skip until we find resolution
        String line = null;
        while (true) {
            line = readLine();
            if (line == null) {
                throw new StbFailureException("Invalid HDR file - no resolution");
            }
            if (line.contains("+X") || line.contains("-X")) {
                break;
            }
        }

        // Parse resolution - format varies

        line = line.trim();
        String[] parts = line.split("\\s+");

        int orientationY = 1;
        int orientationX = 1;

        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("-Y")) {
                orientationY = -1;
                height = Integer.parseInt(parts[i + 1]);
            } else if (parts[i].equals("+Y")) {
                height = Integer.parseInt(parts[i + 1]);
            } else if (parts[i].equals("-X")) {
                orientationX = -1;
                width = Integer.parseInt(parts[i + 1]);
            } else if (parts[i].equals("+X")) {
                width = Integer.parseInt(parts[i + 1]);
            }
        }

        if (width <= 0 || height <= 0) {
            throw new StbFailureException("Invalid HDR dimensions");
        }
    }

    private void decodeOldFormat(ByteBuffer output, int outChannels) {
        // Old format: scanlines without RLE
        for (int y = 0; y < height; y++) {
            // Check for new RLE marker
            if (pos < buffer.limit() - 1) {
                int check = buffer.get(pos) & 0xFF;
                if (check == 0x00) {
                    // Might be RLE - check next byte
                    int check2 = buffer.get(pos + 1) & 0xFF;
                    if (check2 == 0x00) {
                        // It's RLE
                        decodeOneScanlineRLE(output, y, outChannels);
                        continue;
                    }
                }
            }

            // Old format: scanline of RGBE
            for (int x = 0; x < width; x++) {
                int r = readU8();
                int g = readU8();
                int b = readU8();
                int e = readU8();

                float[] rgb = rgbeToFloat(r, g, b, e);
                int outPos = (y * width + x) * outChannels * 4;
                output.putFloat(outPos, rgb[0]);
                output.putFloat(outPos + 4, rgb[1]);
                output.putFloat(outPos + 8, rgb[2]);
            }
        }
    }

    private void decodeRLE(ByteBuffer output, int outChannels) {
        // RLE format: each channel is encoded separately
        // Read each scanline
        for (int y = 0; y < height; y++) {
            // Check for old format RLE marker
            if (pos + 1 < buffer.limit() && buffer.get(pos) == 0 && buffer.get(pos + 1) == 2) {
                // Old format RLE
                decodeOneScanlineRLE(output, y, outChannels);
            } else {
                // New RLE format
                int[] scanlines = new int[width * 4];
                int[] channel = new int[width];

                // Read each channel
                for (int c = 0; c < 4; c++) {
                    int xi = 0;

                    while (xi < width) {
                        if (pos >= buffer.limit()) break;

                        int header = readU8();
                        if (header > 128) {
                            // RLE run
                            int count = header & 0x7F;
                            int val = readU8();
                            for (int i = 0; i < count && xi < width; i++) {
                                channel[xi++] = val;
                            }
                        } else if (header > 0) {
                            // Non-RLE run
                            int count = header;
                            for (int i = 0; i < count && xi < width; i++) {
                                channel[xi++] = readU8();
                            }
                        }
                    }

                    // Copy to scanline - reuse xi
                    for (xi = 0; xi < width; xi++) {
                        scanlines[xi * 4 + c] = channel[xi];
                    }
                }

                // Convert to float
                for (int xi = 0; xi < width; xi++) {
                    int r = scanlines[xi * 4];
                    int g = scanlines[xi * 4 + 1];
                    int b = scanlines[xi * 4 + 2];
                    int e = scanlines[xi * 4 + 3];

                    float[] rgb = rgbeToFloat(r, g, b, e);
                    int outPos = (y * width + xi) * outChannels * 4;
                    output.putFloat(outPos, rgb[0]);
                    if (outChannels > 1) {
                        output.putFloat(outPos + 4, rgb[1]);
                        output.putFloat(outPos + 8, rgb[2]);
                    }
                }
            }
        }
    }

    private void decodeOneScanlineRLE(ByteBuffer output, int y, int outChannels) {
        // Old format RLE
        if (readU8() != 0 || readU8() != 2) {
            throw new StbFailureException("Invalid RLE scanline");
        }

        int[] scanline = new int[width * 4];
        int[] channel = new int[width];

        // Each channel is RLE encoded
        for (int c = 0; c < 4; c++) {
            int xi = 0;
            while (xi < width) {
                if (pos >= buffer.limit()) break;

                int count = readU8();
                if (count > 128) {
                    // RLE run
                    count = (count & 0x7F) + 1;
                    int val = readU8();
                    for (int i = 0; i < count && xi < width; i++) {
                        channel[xi++] = val;
                    }
                } else if (count > 0) {
                    // Non-RLE
                    for (int i = 0; i < count; i++) {
                        channel[xi++] = readU8();
                    }
                }
            }

            // Copy to scanline - reuse xi
            for (xi = 0; xi < width; xi++) {
                scanline[xi * 4 + c] = channel[xi];
            }
        }

        // Convert to float
        for (int xi = 0; xi < width; xi++) {
            int r = scanline[xi * 4];
            int g = scanline[xi * 4 + 1];
            int b = scanline[xi * 4 + 2];
            int e = scanline[xi * 4 + 3];

            float[] rgb = rgbeToFloat(r, g, b, e);
            int outPos = (y * width + xi) * outChannels * 4;
            output.putFloat(outPos, rgb[0]);
            if (outChannels > 1) {
                output.putFloat(outPos + 4, rgb[1]);
                output.putFloat(outPos + 8, rgb[2]);
            }
        }
    }

    private float[] rgbeToFloat(int r, int g, int b, int e) {
        float[] result = new float[3];

        if (e == 0) {
            result[0] = result[1] = result[2] = 0;
            return result;
        }

        // Decode exponent
        int exp = e - (128 + 8);
        float scale = (float) Math.scalb(1.0f, exp);

        // Decode mantissa
        result[0] = ((r + 0.5f) / 256.0f) * scale;
        result[1] = ((g + 0.5f) / 256.0f) * scale;
        result[2] = ((b + 0.5f) / 256.0f) * scale;

        return result;
    }

    private void skipWhitespace() {
        while (pos < buffer.limit()) {
            char c = (char) (buffer.get(pos) & 0xFF);
            if (!Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
    }

    private String readLine() {
        skipWhitespace();

        StringBuilder sb = new StringBuilder();
        while (pos < buffer.limit()) {
            char c = (char) (buffer.get(pos++));
            if (c == '\n' || c == '\r') {
                break;
            }
            sb.append(c);
        }

        return sb.toString();
    }

    private int readU8() {
        if (pos >= buffer.limit()) return 0;
        return buffer.get(pos++) & 0xFF;
    }
}
