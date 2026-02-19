package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/**
 * Radiance HDR decoder, RGBE to float conversion.
 */
public class HdrDecoder implements StbDecoder {

    private static final String HDR_SIGNATURE = "#?RADIANCE\n";
    private static final String HDR_SIGNATURE_ALT = "#?RGBE\n";

    private final ByteBuffer buffer;
    private final IntFunction<ByteBuffer> allocator;
    private final boolean flipVertically;

    private int pos;
    private int width;
    private int height;

    /**
     * Tests if the source starts with a Radiance HDR signature.
     *
     * @param buffer source bytes
     * @return true if HDR signature is present
     */
    public static boolean isHdr(ByteBuffer buffer) {
        ByteBuffer probe = buffer.duplicate();
        probe.position(0);
        return startsWith(probe, HDR_SIGNATURE) || startsWith(probe, HDR_SIGNATURE_ALT);
    }

    /**
     * Creates an HDR decoder instance.
     *
     * @param buffer source data
     * @param allocator output allocator
     * @param flipVertically true to vertically flip decoded output
     */
    public HdrDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate();
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageInfo info() {
        int savedPos = pos;
        int savedW = width;
        int savedH = height;
        try {
            parseHeader();
            return new StbImageInfo(width, height, 3, false, StbImageInfo.ImageFormat.HDR);
        } catch (RuntimeException e) {
            return null;
        } finally {
            pos = savedPos;
            width = savedW;
            height = savedH;
        }
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
        pos = 0;
        parseHeader();

        StbImage.validateDimensions(width, height);

        int reqComp = (desiredChannels == 0) ? 3 : desiredChannels;
        if (reqComp < 1 || reqComp > 4) {
            throw new StbFailureException("Bad desired channels: " + reqComp);
        }

        ByteBuffer output = allocator.apply(width * height * reqComp * 4);

        if (width < 8 || width >= 32768) {
            decodeFlat(output, reqComp);
        } else {
            decodeRle(output, reqComp);
        }

        output.limit(width * height * reqComp * 4);
        output.position(0);

        if (flipVertically) {
            output = StbImage.verticalFlip(getAllocator(), output, width, height, reqComp, 4);
        }

        return new StbImageResult(output, width, height, reqComp, desiredChannels, false, true);
    }

    private void parseHeader() {
        String token = readTokenLine();
        if (!"#?RADIANCE".equals(token) && !"#?RGBE".equals(token)) {
            throw new StbFailureException("not HDR");
        }

        boolean validFormat = false;
        while (true) {
            token = readTokenLine();
            if (token.isEmpty()) {
                break;
            }
            if ("FORMAT=32-bit_rle_rgbe".equals(token)) {
                validFormat = true;
            }
        }

        if (!validFormat) {
            throw new StbFailureException("Unsupported HDR format");
        }

        token = readTokenLine();
        ParsedResolution resolution = parseResolution(token);
        width = resolution.width;
        height = resolution.height;
    }

    private ParsedResolution parseResolution(String token) {
        if (!token.startsWith("-Y ")) {
            throw new StbFailureException("Unsupported HDR data layout");
        }

        int idx = 3;
        int hStart = idx;
        while (idx < token.length() && Character.isDigit(token.charAt(idx))) {
            idx++;
        }
        if (idx == hStart) {
            throw new StbFailureException("Invalid HDR dimensions");
        }
        int parsedHeight = Integer.parseInt(token.substring(hStart, idx));

        while (idx < token.length() && token.charAt(idx) == ' ') {
            idx++;
        }

        if (!token.startsWith("+X ", idx)) {
            throw new StbFailureException("Unsupported HDR data layout");
        }
        idx += 3;

        int wStart = idx;
        while (idx < token.length() && Character.isDigit(token.charAt(idx))) {
            idx++;
        }
        if (idx == wStart) {
            throw new StbFailureException("Invalid HDR dimensions");
        }

        int parsedWidth = Integer.parseInt(token.substring(wStart, idx));
        if (parsedWidth <= 0 || parsedHeight <= 0) {
            throw new StbFailureException("Invalid HDR dimensions");
        }

        return new ParsedResolution(parsedWidth, parsedHeight);
    }

    private void decodeFlat(ByteBuffer output, int reqComp) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = readU8();
                int g = readU8();
                int b = readU8();
                int e = readU8();
                putConverted(output, (y * width + x) * reqComp * 4, reqComp, r, g, b, e);
            }
        }
    }

    private void decodeRle(ByteBuffer output, int reqComp) {
        byte[] scanline = null;

        for (int y = 0; y < height; y++) {
            int c1 = readU8();
            int c2 = readU8();
            int len = readU8();

            if (c1 != 2 || c2 != 2 || (len & 0x80) != 0) {
                int e = readU8();
                putConverted(output, 0, reqComp, c1, c2, len, e);
                int totalPixels = width * height;
                for (int pixel = 1; pixel < totalPixels; pixel++) {
                    int r = readU8();
                    int g = readU8();
                    int b = readU8();
                    int exp = readU8();
                    putConverted(output, pixel * reqComp * 4, reqComp, r, g, b, exp);
                }
                return;
            }

            len = (len << 8) | readU8();
            if (len != width) {
                throw new StbFailureException("corrupt HDR");
            }

            if (scanline == null) {
                scanline = new byte[width * 4];
            }

            for (int k = 0; k < 4; k++) {
                int i = 0;
                while (i < width) {
                    int count = readU8();
                    int nLeft = width - i;
                    if (count > 128) {
                        int value = readU8();
                        count -= 128;
                        if (count == 0 || count > nLeft) {
                            throw new StbFailureException("bad RLE data in HDR");
                        }
                        for (int z = 0; z < count; z++) {
                            scanline[(i++ * 4) + k] = (byte) value;
                        }
                    } else {
                        if (count == 0 || count > nLeft) {
                            throw new StbFailureException("bad RLE data in HDR");
                        }
                        for (int z = 0; z < count; z++) {
                            scanline[(i++ * 4) + k] = (byte) readU8();
                        }
                    }
                }
            }

            for (int x = 0; x < width; x++) {
                int base = x * 4;
                int r = Byte.toUnsignedInt(scanline[base]);
                int g = Byte.toUnsignedInt(scanline[base + 1]);
                int b = Byte.toUnsignedInt(scanline[base + 2]);
                int e = Byte.toUnsignedInt(scanline[base + 3]);
                putConverted(output, (y * width + x) * reqComp * 4, reqComp, r, g, b, e);
            }
        }
    }

    private void putConverted(ByteBuffer output, int outBase, int reqComp, int r, int g, int b, int e) {
        if (e != 0) {
            float f = (float) Math.scalb(1.0f, e - (128 + 8));
            if (reqComp <= 2) {
                output.putFloat(outBase, (r + g + b) * f / 3.0f);
                if (reqComp == 2) {
                    output.putFloat(outBase + 4, 1.0f);
                }
            } else {
                output.putFloat(outBase, r * f);
                output.putFloat(outBase + 4, g * f);
                output.putFloat(outBase + 8, b * f);
                if (reqComp == 4) {
                    output.putFloat(outBase + 12, 1.0f);
                }
            }
            return;
        }

        switch (reqComp) {
            case 4:
                output.putFloat(outBase + 12, 1.0f);
                output.putFloat(outBase, 0.0f);
                output.putFloat(outBase + 4, 0.0f);
                output.putFloat(outBase + 8, 0.0f);
                break;
            case 3:
                output.putFloat(outBase, 0.0f);
                output.putFloat(outBase + 4, 0.0f);
                output.putFloat(outBase + 8, 0.0f);
                break;
            case 2:
                output.putFloat(outBase + 4, 1.0f);
                output.putFloat(outBase, 0.0f);
                break;
            case 1:
                output.putFloat(outBase, 0.0f);
                break;
            default:
                throw new StbFailureException("Bad desired channels: " + reqComp);
        }
    }

    private int readU8() {
        if (pos >= buffer.limit()) {
            throw new StbFailureException("corrupt HDR");
        }
        return buffer.get(pos++) & 0xFF;
    }

    private String readTokenLine() {
        StringBuilder sb = new StringBuilder();
        while (pos < buffer.limit()) {
            char c = (char) (buffer.get(pos++) & 0xFF);
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean startsWith(ByteBuffer probe, String signature) {
        byte[] bytes = signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (probe.remaining() < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (probe.get(i) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static final class ParsedResolution {
        private final int width;
        private final int height;

        private ParsedResolution(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
}
