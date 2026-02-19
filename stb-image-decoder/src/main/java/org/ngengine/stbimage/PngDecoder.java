package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.io.ByteArrayOutputStream;


/**
 * PNG decoder with zlib decompression.
 * Supports 1/2/4/8/16-bit depths, all color types (grayscale, RGB, RGBA, palette).
 */
public class PngDecoder implements StbDecoder {

    private static final int PNG_CHUNK_IHDR = 0x49484452;
    private static final int PNG_CHUNK_IDAT = 0x49444154;
    private static final int PNG_CHUNK_IEND = 0x49454E44;
    private static final int PNG_CHUNK_PLTE = 0x504C5445;
    private static final int PNG_CHUNK_tRNS = 0x74524E53;
    private static final int PNG_CHUNK_CgBI = 0x43674249;

    // Color types
    private static final int CT_GREY = 0;
    private static final int CT_RGB = 2;
    private static final int CT_INDEXED = 3;
    private static final int CT_GREY_ALPHA = 4;
    private static final int CT_RGBA = 6;

    private ByteBuffer buffer;
    private int pos;
    private IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int bitDepth;
    private int colorType;
    private int compression;
    private int filter;
    private int interlace;

    private byte[] palette;
    private byte[] transparency;
    private boolean convertIphonePngToRgb;
    private boolean unpremultiplyOnLoad;


   
    public static boolean isPng(ByteBuffer buffer) {
        if (buffer.remaining() < 8) return false;
        buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        byte[] sig = new byte[8];
        buffer.get(sig);
        return sig[0] == (byte) 0x89 && sig[1] == 0x50 && sig[2] == 0x4E && sig[3] == 0x47
            && sig[4] == 0x0D && sig[5] == 0x0A && sig[6] == 0x1A && sig[7] == 0x0A;
    }

    public PngDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    public void setConvertIphonePngToRgb(boolean convertIphonePngToRgb) {
        this.convertIphonePngToRgb = convertIphonePngToRgb;
    }

    public void setUnpremultiplyOnLoad(boolean unpremultiplyOnLoad) {
        this.unpremultiplyOnLoad = unpremultiplyOnLoad;
    }

    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    @Override
    public StbImageInfo info() {
        pos = 0;
        if (!readSignature()) {
            return null;
        }

        while (hasRemaining()) {
            int length = readU32BE();
            int type = readU32BE();

            if (type == PNG_CHUNK_IHDR) {
                readIHDR();
                return new StbImageInfo(width, height, getChannels(), bitDepth == 16, StbImageInfo.ImageFormat.PNG);
            } else if (type == PNG_CHUNK_IEND) {
                return null;
            }

            skip(length + 4); // data + CRC
        }
        return null;
    }

    @Override
    public StbImageResult load(int desiredChannels) {
        pos = 0;
        if (!readSignature()) {
            throw new StbFailureException("PNG signature not found");
        }

        boolean foundIHDR = false;
        boolean isIphonePng = false;
        ByteArrayOutputStream idat = new ByteArrayOutputStream(8192);

        while (hasRemaining()) {
            int length = readU32BE();
            int type = readU32BE();
            if (length < 0 || pos + length + 4 > buffer.limit()) {
                throw new StbFailureException("Corrupt PNG chunk length");
            }

            if (type == PNG_CHUNK_IHDR) {
                readIHDR();
                foundIHDR = true;
            } else if (type == PNG_CHUNK_CgBI) {
                isIphonePng = true;
                skip(length);
            } else if (type == PNG_CHUNK_PLTE) {
                readPLTE(length);
            } else if (type == PNG_CHUNK_tRNS) {
                readTRNS(length);
            } else if (type == PNG_CHUNK_IDAT) {
                if (!foundIHDR) {
                    throw new StbFailureException("IDAT before IHDR");
                }
                byte[] tmp = new byte[length];
                buffer.get(pos, tmp, 0, length);
                idat.write(tmp, 0, length);
                pos += length;
            } else if (type == PNG_CHUNK_IEND) {
                break;
            } else {
                skip(length);
            }

            skip(4); // CRC
        }

        if (!foundIHDR) {
            throw new StbFailureException("No IHDR chunk found");
        }

        byte[] idatBytes = idat.toByteArray();
        if (idatBytes.length == 0) {
            throw new StbFailureException("No IDAT chunk found");
        }

        // Decompress
        ByteBuffer decompressed = decompress(ByteBuffer.wrap(idatBytes), isIphonePng);

        // Decode image data
        ByteBuffer imageData = (interlace == 1)
            ? decodeInterlacedImageData(decompressed, width, height)
            : decodeImageData(decompressed, width, height);
        int srcChannels = getChannels();
        if (transparency != null && (colorType == CT_GREY || colorType == CT_RGB)) {
            imageData = applyColorKeyTransparency(imageData, srcChannels, width, height, bitDepth == 16);
            srcChannels += 1;
        }
        if (isIphonePng && convertIphonePngToRgb && srcChannels > 2) {
            deIphone(imageData, srcChannels, width, height, bitDepth == 16);
        }

        // Convert to desired channels
        if (desiredChannels == 0) {
            desiredChannels = (colorType == CT_GREY || colorType == CT_GREY_ALPHA) ? 1 : (colorType == CT_INDEXED ? 3 : srcChannels);
        }

        ByteBuffer result = StbImage.convertChannels(getAllocator(), imageData, srcChannels, width, height, desiredChannels, bitDepth == 16);

        boolean output16 = bitDepth == 16;
        if (output16) {
            result = convert16To8(result, width, height, desiredChannels);
            output16 = false;
        }

        if (flipVertically) {
            result = StbImage.verticalFlip(getAllocator(), result, width, height, desiredChannels, output16);
        }

        return new StbImageResult(result, width, height, desiredChannels, desiredChannels, output16, false);
    }

    private boolean readSignature() {
        byte[] sig = new byte[] {(byte)0x89, (byte)0x50, (byte)0x4E, (byte)0x47, (byte)0x0D, (byte)0x0A, (byte)0x1A, (byte)0x0A};
        for (byte b : sig) {
            if (readU8() != (b & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private void readIHDR() {
        width = readU32BE();
        height = readU32BE();
        bitDepth = readU8();
        colorType = readU8();
        compression = readU8();
        filter = readU8();
        interlace = readU8();

        if (bitDepth < 1 || bitDepth > 16) {
            throw new StbFailureException("Invalid bit depth: " + bitDepth);
        }
        if (colorType > 6) {
            throw new StbFailureException("Invalid color type: " + colorType);
        }
        if (compression != 0) {
            throw new StbFailureException("Unsupported PNG compression method: " + compression);
        }
        if (filter != 0) {
            throw new StbFailureException("Unsupported PNG filter method: " + filter);
        }
        if (interlace != 0 && interlace != 1) {
            throw new StbFailureException("Unsupported PNG interlace method: " + interlace);
        }
    }

    private void readPLTE(int length) {
        if (colorType != CT_INDEXED) {
            skip(length);
            return;
        }
        palette = new byte[Math.min(length, 768)];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = (byte) readU8();
        }
        skip(length - palette.length);
    }

    private void readTRNS(int length) {
        if (colorType == CT_INDEXED) {
            transparency = new byte[Math.min(length, 256)];
            for (int i = 0; i < transparency.length; i++) {
                transparency[i] = (byte) readU8();
            }
            skip(length - transparency.length);
        } else if (colorType == CT_GREY) {
            transparency = new byte[2];
            transparency[0] = (byte) readU8();
            transparency[1] = (byte) readU8();
            skip(length - 2);
        } else if (colorType == CT_RGB) {
            transparency = new byte[6];
            for (int i = 0; i < 6; i++) {
                transparency[i] = (byte) readU8();
            }
            skip(length - 6);
        } else {
            skip(length);
        }
    }

    private ByteBuffer decompress(ByteBuffer compressed, boolean rawDeflate) {
        Inflater inflater = new Inflater(rawDeflate);
        compressed.rewind();
        byte[] input = new byte[compressed.remaining()];
        compressed.get(input);
        inflater.setInput(input);

        try {
            // Use a larger buffer and inflate until finished
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && !inflater.finished()) {
                    // Check for needing more input
                    if (inflater.needsInput()) {
                        throw new StbFailureException("Zlib decompression: needs more input");
                    }
                }
                baos.write(buffer, 0, n);
            }
            return ByteBuffer.wrap(baos.toByteArray());
        } catch (DataFormatException e) {
            throw new StbFailureException("Zlib decompression failed: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private int getScanlineSize(int width) {
        int bitsPerPixel = getBitsPerPixel();
        return ((bitsPerPixel * width + 7) >> 3) + 1; // +1 for filter byte
    }

    private int getBitsPerPixel() {
        switch (colorType) {
            case CT_GREY: return bitDepth;
            case CT_RGB: return bitDepth * 3;
            case CT_INDEXED: return bitDepth;
            case CT_GREY_ALPHA: return bitDepth * 2;
            case CT_RGBA: return bitDepth * 4;
            default: return bitDepth * 4;
        }
    }

    private int getChannels() {
        switch (colorType) {
            case CT_GREY: return 1;
            case CT_RGB: return 3;
            case CT_INDEXED: return 4; // Convert to RGBA
            case CT_GREY_ALPHA: return 2;
            case CT_RGBA: return 4;
            default: return 4;
        }
    }

    private ByteBuffer decodeImageData(ByteBuffer data, int width, int height) {
        int channels = getChannels();
        int bytesPerChannel = bitDepth == 16 ? 2 : 1;
        int outSize = width * height * channels * bytesPerChannel;
        ByteBuffer output = allocator.apply(outSize);
        int filterBytesPerPixel = getFilterBytesPerPixel();

        int scanlineSize = getScanlineSize(width);
        byte[] prevScanline = new byte[scanlineSize];
        byte[] curScanline = new byte[scanlineSize];

        for (int y = 0; y < height; y++) {
            int filterType = data.get() & 0xFF;
            for (int i = 0; i < scanlineSize - 1; i++) {
                curScanline[i] = data.get();
            }

            // Unfilter
            int stride = filterBytesPerPixel;
            switch (filterType) {
                case 0: // None
                    break;
                case 1: // Sub
                    for (int i = 0; i < scanlineSize - 1; i++) {
                        int left = (i >= stride) ? curScanline[i - stride] : 0;
                        curScanline[i] = (byte) ((curScanline[i] & 0xFF) + (left & 0xFF));
                    }
                    break;
                case 2: // Up
                    for (int i = 0; i < scanlineSize - 1; i++) {
                        curScanline[i] = (byte) ((curScanline[i] & 0xFF) + (prevScanline[i] & 0xFF));
                    }
                    break;
                case 3: // Average
                    for (int i = 0; i < scanlineSize - 1; i++) {
                        int left = (i >= stride) ? curScanline[i - stride] : 0;
                        int a = left & 0xFF;
                        int b = prevScanline[i] & 0xFF;
                        curScanline[i] = (byte) ((curScanline[i] & 0xFF) + ((a + b) >> 1));
                    }
                    break;
                case 4: // Paeth
                    for (int i = 0; i < scanlineSize - 1; i++) {
                        int a = (i >= stride) ? curScanline[i - stride] : 0;
                        int b = prevScanline[i];
                        int c = (i >= stride) ? prevScanline[i - stride] : 0;
                        curScanline[i] = (byte) ((curScanline[i] & 0xFF) + paethPredictor(a & 0xFF, b & 0xFF, c & 0xFF));
                    }
                    break;
            }

            // Convert scanline to output pixels
            convertScanline(curScanline, output, y, width, channels, bytesPerChannel);

            // Swap scanlines
            byte[] temp = prevScanline;
            prevScanline = curScanline;
            curScanline = temp;
        }

        // Set limit to the total bytes written (since absolute puts don't advance position)
        output.limit(outSize);
        output.position(0);
        return output;
    }

    private ByteBuffer decodeInterlacedImageData(ByteBuffer data, int width, int height) {
        int channels = getChannels();
        int bytesPerChannel = bitDepth == 16 ? 2 : 1;
        ByteBuffer output = allocator.apply(width * height * channels * bytesPerChannel);
        int bitsPerPixel = getBitsPerPixel();
        int filterBytesPerPixel = getFilterBytesPerPixel();

        int[] xOrig = {0, 4, 0, 2, 0, 1, 0};
        int[] yOrig = {0, 0, 4, 0, 2, 0, 1};
        int[] xStep = {8, 8, 4, 4, 2, 2, 1};
        int[] yStep = {8, 8, 8, 4, 4, 2, 2};

        for (int pass = 0; pass < 7; pass++) {
            int pw = (width - xOrig[pass] + xStep[pass] - 1) / xStep[pass];
            int ph = (height - yOrig[pass] + yStep[pass] - 1) / yStep[pass];
            if (pw <= 0 || ph <= 0) {
                continue;
            }

            int scanlineSize = ((bitsPerPixel * pw + 7) >> 3) + 1;
            byte[] prev = new byte[scanlineSize - 1];
            byte[] cur = new byte[scanlineSize - 1];
            ByteBuffer rowOut = allocator.apply(pw * channels * bytesPerChannel);

            for (int py = 0; py < ph; py++) {
                int filterType = data.get() & 0xFF;
                for (int i = 0; i < scanlineSize - 1; i++) {
                    cur[i] = data.get();
                }

                switch (filterType) {
                    case 0:
                        break;
                    case 1:
                        for (int i = 0; i < cur.length; i++) {
                            int left = (i >= filterBytesPerPixel) ? cur[i - filterBytesPerPixel] : 0;
                            cur[i] = (byte) ((cur[i] & 0xFF) + (left & 0xFF));
                        }
                        break;
                    case 2:
                        for (int i = 0; i < cur.length; i++) {
                            cur[i] = (byte) ((cur[i] & 0xFF) + (prev[i] & 0xFF));
                        }
                        break;
                    case 3:
                        for (int i = 0; i < cur.length; i++) {
                            int left = (i >= filterBytesPerPixel) ? cur[i - filterBytesPerPixel] : 0;
                            int up = prev[i] & 0xFF;
                            cur[i] = (byte) ((cur[i] & 0xFF) + (((left & 0xFF) + up) >> 1));
                        }
                        break;
                    case 4:
                        for (int i = 0; i < cur.length; i++) {
                            int a = (i >= filterBytesPerPixel) ? cur[i - filterBytesPerPixel] & 0xFF : 0;
                            int b = prev[i] & 0xFF;
                            int c = (i >= filterBytesPerPixel) ? prev[i - filterBytesPerPixel] & 0xFF : 0;
                            cur[i] = (byte) ((cur[i] & 0xFF) + paethPredictor(a, b, c));
                        }
                        break;
                    default:
                        throw new StbFailureException("Invalid PNG filter");
                }

                rowOut.clear();
                convertScanline(cur, rowOut, 0, pw, channels, bytesPerChannel);
                for (int px = 0; px < pw; px++) {
                    int dstX = xOrig[pass] + px * xStep[pass];
                    int dstY = yOrig[pass] + py * yStep[pass];
                    int dstBase = (dstY * width + dstX) * channels * bytesPerChannel;
                    int srcBase = px * channels * bytesPerChannel;
                    for (int i = 0; i < channels * bytesPerChannel; i++) {
                        output.put(dstBase + i, rowOut.get(srcBase + i));
                    }
                }

                byte[] t = prev;
                prev = cur;
                cur = t;
            }
        }

        output.limit(width * height * channels * bytesPerChannel);
        output.position(0);
        return output;
    }

    private ByteBuffer convert16To8(ByteBuffer src16, int width, int height, int channels) {
        int count = width * height * channels;
        ByteBuffer out = allocator.apply(count);
        for (int i = 0; i < count; i++) {
            int v = Short.toUnsignedInt(src16.getShort(i * 2));
            out.put(i, (byte) (v >> 8));
        }
        out.limit(count);
        out.position(0);
        return out;
    }

    private void deIphone(ByteBuffer data, int channels, int width, int height, boolean is16Bit) {
        int pixelCount = width * height;
        if (!is16Bit) {
            if (channels == 3) {
                for (int i = 0; i < pixelCount; i++) {
                    int p = i * 3;
                    byte t = data.get(p);
                    data.put(p, data.get(p + 2));
                    data.put(p + 2, t);
                }
            } else if (channels == 4) {
                if (unpremultiplyOnLoad) {
                    for (int i = 0; i < pixelCount; i++) {
                        int p = i * 4;
                        int a = Byte.toUnsignedInt(data.get(p + 3));
                        int t = Byte.toUnsignedInt(data.get(p));
                        if (a != 0) {
                            int half = a / 2;
                            int b = Byte.toUnsignedInt(data.get(p + 2));
                            int g = Byte.toUnsignedInt(data.get(p + 1));
                            data.put(p, (byte) ((b * 255 + half) / a));
                            data.put(p + 1, (byte) ((g * 255 + half) / a));
                            data.put(p + 2, (byte) ((t * 255 + half) / a));
                        } else {
                            data.put(p, data.get(p + 2));
                            data.put(p + 2, (byte) t);
                        }
                    }
                } else {
                    for (int i = 0; i < pixelCount; i++) {
                        int p = i * 4;
                        byte t = data.get(p);
                        data.put(p, data.get(p + 2));
                        data.put(p + 2, t);
                    }
                }
            }
            return;
        }

        int stride = channels * 2;
        for (int i = 0; i < pixelCount; i++) {
            int p = i * stride;
            short t = data.getShort(p);
            data.putShort(p, data.getShort(p + 4));
            data.putShort(p + 4, t);
        }
    }

    private int getFilterBytesPerPixel() {
        int rawChannels;
        switch (colorType) {
            case CT_GREY:
            case CT_INDEXED:
                rawChannels = 1;
                break;
            case CT_RGB:
                rawChannels = 3;
                break;
            case CT_GREY_ALPHA:
                rawChannels = 2;
                break;
            case CT_RGBA:
                rawChannels = 4;
                break;
            default:
                rawChannels = 1;
                break;
        }
        int bits = bitDepth * rawChannels;
        int bytes = (bits + 7) >> 3;
        return Math.max(1, bytes);
    }

    private void convertScanline(byte[] scanline, ByteBuffer output, int y, int width, int channels, int bytesPerChannel) {
        int outOffset = y * width * channels * bytesPerChannel;

        switch (colorType) {
            case CT_GREY:
                convertGrey(scanline, output, outOffset, width, bytesPerChannel);
                break;
            case CT_RGB:
                convertRgb(scanline, output, outOffset, width, bytesPerChannel);
                break;
            case CT_INDEXED:
                convertIndexed(scanline, output, outOffset, width, bytesPerChannel);
                break;
            case CT_GREY_ALPHA:
                convertGreyAlpha(scanline, output, outOffset, width, bytesPerChannel);
                break;
            case CT_RGBA:
                convertRgba(scanline, output, outOffset, width, bytesPerChannel);
                break;
        }
    }

    private void convertGrey(byte[] scanline, ByteBuffer output, int offset, int width, int bytesPerChannel) {
        int bitsPerPixel = bitDepth;
        int samplesPerByte = 8 / bitsPerPixel;

        for (int x = 0; x < width; x++) {
            int val;
            if (bitsPerPixel == 16) {
                val = ((scanline[x * 2] & 0xFF) << 8) | (scanline[x * 2 + 1] & 0xFF);
                if (bytesPerChannel == 2) {
                    output.putShort(offset + x * 2, (short) val);
                } else {
                    output.put(offset + x, (byte) (val >> 8));
                }
            } else if (bitsPerPixel < 8) {
                int byteIndex = x / samplesPerByte;
                int bitShift = 8 - bitsPerPixel * (x % samplesPerByte + 1);
                val = (scanline[byteIndex] >> bitShift) & ((1 << bitsPerPixel) - 1);
                if (bitDepth < 8) {
                    val = (val * 255) / ((1 << bitDepth) - 1);
                }
                output.put(offset + x, (byte) val);
            } else {
                val = scanline[x] & 0xFF;
                if (bitDepth == 8) {
                    output.put(offset + x, (byte) val);
                }
            }
        }
    }

    private void convertRgb(byte[] scanline, ByteBuffer output, int offset, int width, int bytesPerChannel) {
        int stride = bitDepth == 16 ? 6 : 3;

        for (int x = 0; x < width; x++) {
            int pos = x * stride;
            if (bitDepth == 16) {
                int r = ((scanline[pos] & 0xFF) << 8) | (scanline[pos + 1] & 0xFF);
                int g = ((scanline[pos + 2] & 0xFF) << 8) | (scanline[pos + 3] & 0xFF);
                int b = ((scanline[pos + 4] & 0xFF) << 8) | (scanline[pos + 5] & 0xFF);
                if (bytesPerChannel == 2) {
                    output.putShort(offset + x * 6, (short) r);
                    output.putShort(offset + x * 6 + 2, (short) g);
                    output.putShort(offset + x * 6 + 4, (short) b);
                } else {
                    output.put(offset + x * 3, (byte) (r >> 8));
                    output.put(offset + x * 3 + 1, (byte) (g >> 8));
                    output.put(offset + x * 3 + 2, (byte) (b >> 8));
                }
            } else {
                int r = scanline[pos] & 0xFF;
                int g = scanline[pos + 1] & 0xFF;
                int b = scanline[pos + 2] & 0xFF;
                output.put(offset + x * 3, (byte) r);
                output.put(offset + x * 3 + 1, (byte) g);
                output.put(offset + x * 3 + 2, (byte) b);
            }
        }
    }

    private void convertIndexed(byte[] scanline, ByteBuffer output, int offset, int width, int bytesPerChannel) {
        int bitsPerPixel = bitDepth;
        int samplesPerByte = 8 / bitsPerPixel;

        for (int x = 0; x < width; x++) {
            int idx;
            if (bitsPerPixel < 8) {
                int byteIndex = x / samplesPerByte;
                int bitShift = 8 - bitsPerPixel * (x % samplesPerByte + 1);
                idx = (scanline[byteIndex] >> bitShift) & ((1 << bitsPerPixel) - 1);
            } else {
                idx = scanline[x] & 0xFF;
            }

            int p = idx * 3;
            byte r = (palette != null && p < palette.length) ? palette[p] : 0;
            byte g = (palette != null && p + 1 < palette.length) ? palette[p + 1] : 0;
            byte b = (palette != null && p + 2 < palette.length) ? palette[p + 2] : 0;
            byte a = (byte) 0xFF;

            if (transparency != null && idx < transparency.length) {
                a = transparency[idx];
            }

            int pos = x * 4;
            output.put(offset + pos, r);
            output.put(offset + pos + 1, g);
            output.put(offset + pos + 2, b);
            output.put(offset + pos + 3, a);
        }
    }

    private void convertGreyAlpha(byte[] scanline, ByteBuffer output, int offset, int width, int bytesPerChannel) {
        int stride = bitDepth == 16 ? 4 : 2;

        for (int x = 0; x < width; x++) {
            int pos = x * stride;
            if (bitDepth == 16) {
                int g = ((scanline[pos] & 0xFF) << 8) | (scanline[pos + 1] & 0xFF);
                int a = ((scanline[pos + 2] & 0xFF) << 8) | (scanline[pos + 3] & 0xFF);
                if (bytesPerChannel == 2) {
                    output.putShort(offset + x * 4, (short) g);
                    output.putShort(offset + x * 4 + 2, (short) a);
                } else {
                    output.put(offset + x * 2, (byte) (g >> 8));
                    output.put(offset + x * 2 + 1, (byte) (a >> 8));
                }
            } else {
                output.put(offset + x * 2, scanline[pos]);
                output.put(offset + x * 2 + 1, scanline[pos + 1]);
            }
        }
    }

    private void convertRgba(byte[] scanline, ByteBuffer output, int offset, int width, int bytesPerChannel) {
        int stride = bitDepth == 16 ? 8 : 4;

        for (int x = 0; x < width; x++) {
            int pos = x * stride;
            if (bitDepth == 16) {
                int r = ((scanline[pos] & 0xFF) << 8) | (scanline[pos + 1] & 0xFF);
                int g = ((scanline[pos + 2] & 0xFF) << 8) | (scanline[pos + 3] & 0xFF);
                int b = ((scanline[pos + 4] & 0xFF) << 8) | (scanline[pos + 5] & 0xFF);
                int a = ((scanline[pos + 6] & 0xFF) << 8) | (scanline[pos + 7] & 0xFF);
                if (bytesPerChannel == 2) {
                    output.putShort(offset + x * 8, (short) r);
                    output.putShort(offset + x * 8 + 2, (short) g);
                    output.putShort(offset + x * 8 + 4, (short) b);
                    output.putShort(offset + x * 8 + 6, (short) a);
                } else {
                    output.put(offset + x * 4, (byte) (r >> 8));
                    output.put(offset + x * 4 + 1, (byte) (g >> 8));
                    output.put(offset + x * 4 + 2, (byte) (b >> 8));
                    output.put(offset + x * 4 + 3, (byte) (a >> 8));
                }
            } else {
                output.put(offset + x * 4, scanline[pos]);
                output.put(offset + x * 4 + 1, scanline[pos + 1]);
                output.put(offset + x * 4 + 2, scanline[pos + 2]);
                output.put(offset + x * 4 + 3, scanline[pos + 3]);
            }
        }
    }

    private int paethPredictor(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        } else if (pb <= pc) {
            return b;
        }
        return c;
    }

    private ByteBuffer applyColorKeyTransparency(ByteBuffer src, int srcChannels, int width, int height, boolean is16Bit) {
        int bytesPerChannel = is16Bit ? 2 : 1;
        int dstChannels = srcChannels + 1;
        ByteBuffer out = allocator.apply(width * height * dstChannels * bytesPerChannel);
        int max = is16Bit ? 0xFFFF : 0xFF;

        int grayKey = -1;
        int rKey = -1;
        int gKey = -1;
        int bKey = -1;
        if (srcChannels == 1 && transparency.length >= 2) {
            int raw = ((transparency[0] & 0xFF) << 8) | (transparency[1] & 0xFF);
            grayKey = is16Bit ? raw : to8bit(raw);
        } else if (srcChannels == 3 && transparency.length >= 6) {
            int rr = ((transparency[0] & 0xFF) << 8) | (transparency[1] & 0xFF);
            int gg = ((transparency[2] & 0xFF) << 8) | (transparency[3] & 0xFF);
            int bb = ((transparency[4] & 0xFF) << 8) | (transparency[5] & 0xFF);
            rKey = is16Bit ? rr : to8bit(rr);
            gKey = is16Bit ? gg : to8bit(gg);
            bKey = is16Bit ? bb : to8bit(bb);
        }

        for (int i = 0; i < width * height; i++) {
            int s = i * srcChannels * bytesPerChannel;
            int d = i * dstChannels * bytesPerChannel;
            if (srcChannels == 1) {
                int g = is16Bit ? Short.toUnsignedInt(src.getShort(s)) : Byte.toUnsignedInt(src.get(s));
                putChannel(out, d, 0, g, is16Bit);
                putChannel(out, d, 1, g == grayKey ? 0 : max, is16Bit);
            } else {
                int r = is16Bit ? Short.toUnsignedInt(src.getShort(s)) : Byte.toUnsignedInt(src.get(s));
                int g = is16Bit ? Short.toUnsignedInt(src.getShort(s + bytesPerChannel)) : Byte.toUnsignedInt(src.get(s + 1));
                int b = is16Bit ? Short.toUnsignedInt(src.getShort(s + bytesPerChannel * 2)) : Byte.toUnsignedInt(src.get(s + 2));
                putChannel(out, d, 0, r, is16Bit);
                putChannel(out, d, 1, g, is16Bit);
                putChannel(out, d, 2, b, is16Bit);
                boolean transparentPixel = r == rKey && g == gKey && b == bKey;
                putChannel(out, d, 3, transparentPixel ? 0 : max, is16Bit);
            }
        }

        out.limit(width * height * dstChannels * bytesPerChannel);
        out.position(0);
        return out;
    }

    private int to8bit(int value16) {
        if (bitDepth == 16) {
            return (value16 >> 8) & 0xFF;
        }
        int maxIn = (1 << bitDepth) - 1;
        int low = value16 & maxIn;
        return (low * 255) / maxIn;
    }

    private void putChannel(ByteBuffer dst, int base, int channel, int value, boolean is16Bit) {
        int off = base + (is16Bit ? channel * 2 : channel);
        if (is16Bit) {
            dst.putShort(off, (short) value);
        } else {
            dst.put(off, (byte) value);
        }
    }

    private int readU8() {
        return buffer.get(pos++) & 0xFF;
    }

    private int readU32BE() {
        int b0 = buffer.get(pos++) & 0xFF;
        int b1 = buffer.get(pos++) & 0xFF;
        int b2 = buffer.get(pos++) & 0xFF;
        int b3 = buffer.get(pos++) & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private void skip(int n) {
        pos += n;
    }

    private boolean hasRemaining() {
        return pos < buffer.limit();
    }

}
