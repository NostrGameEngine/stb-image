package com.stb.image;

import com.stb.image.allocator.StbAllocator;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PNG decoder with zlib decompression.
 * Supports 1/2/4/8/16-bit depths, all color types (grayscale, RGB, RGBA, palette).
 */
public class PngDecoder {

    private static final int PNG_SIGNATURE = 0x89504E47;
    private static final int PNG_CHUNK_IHDR = 0x49484452;
    private static final int PNG_CHUNK_IDAT = 0x49444154;
    private static final int PNG_CHUNK_IEND = 0x49454E44;
    private static final int PNG_CHUNK_PLTE = 0x504C5445;
    private static final int PNG_CHUNK_tRNS = 0x74524E53;

    // Color types
    private static final int CT_GREY = 0;
    private static final int CT_RGB = 2;
    private static final int CT_INDEXED = 3;
    private static final int CT_GREY_ALPHA = 4;
    private static final int CT_RGBA = 6;

    private ByteBuffer buffer;
    private int pos;
    private StbAllocator allocator;
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

    public static StbImageInfo getInfo(ByteBuffer buffer) {
        return getInfo(buffer, StbAllocator.DEFAULT);
    }

    public static StbImageInfo getInfo(ByteBuffer buffer, StbAllocator allocator) {
        PngDecoder decoder = new PngDecoder(buffer.duplicate(), allocator, false);
        return decoder.decodeInfo();
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels) {
        return decode(buffer, desiredChannels, StbAllocator.DEFAULT, StbImage.isFlipVertically());
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels, StbAllocator allocator) {
        return decode(buffer, desiredChannels, allocator, StbImage.isFlipVertically());
    }

    public static StbImageResult decode(ByteBuffer buffer, int desiredChannels, StbAllocator allocator, boolean flipVertically) {
        PngDecoder decoder = new PngDecoder(buffer, allocator, flipVertically);
        return decoder.decode(desiredChannels);
    }

    public PngDecoder(ByteBuffer buffer, StbAllocator allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate();
        this.allocator = allocator != null ? allocator : StbAllocator.DEFAULT;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    private StbImageInfo decodeInfo() {
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

    private StbImageResult decode(int desiredChannels) {
        if (!readSignature()) {
            throw new StbFailureException("PNG signature not found");
        }

        boolean foundIHDR = false;
        ByteBuffer idatData = null;
        int idatLength = 0;

        while (hasRemaining()) {
            int length = readU32BE();
            int type = readU32BE();
            int chunkStart = pos;

            if (type == PNG_CHUNK_IHDR) {
                readIHDR();
                foundIHDR = true;
            } else if (type == PNG_CHUNK_PLTE) {
                readPLTE(length);
            } else if (type == PNG_CHUNK_tRNS) {
                readTRNS(length);
            } else if (type == PNG_CHUNK_IDAT) {
                if (!foundIHDR) {
                    throw new StbFailureException("IDAT before IHDR");
                }
                if (idatData == null) {
                    idatData = allocator.allocate(length);
                } else {
                    int oldLimit = idatData.limit();
                    ByteBuffer newData = allocator.allocate(idatData.remaining() + length);
                    newData.put(idatData);
                    idatData = newData;
                    idatData.limit(oldLimit + length);
                    idatData.position(oldLimit);
                }
                readBytes(idatData, length);
                idatLength += length;
            } else if (type == PNG_CHUNK_IEND) {
                break;
            } else {
                skip(length);
            }

            skip(4); // CRC

            if (idatData != null) {
                idatData.flip();
            }
        }

        if (!foundIHDR) {
            throw new StbFailureException("No IHDR chunk found");
        }

        if (idatData == null || idatLength == 0) {
            throw new StbFailureException("No IDAT chunk found");
        }

        // Decompress
        ByteBuffer decompressed = decompress(idatData, width, height);

        // Decode image data
        ByteBuffer imageData = decodeImageData(decompressed, width, height);

        // Convert to desired channels
        int srcChannels = getChannels();
        if (desiredChannels == 0) {
            desiredChannels = (colorType == CT_GREY || colorType == CT_GREY_ALPHA) ? 1 : (colorType == CT_INDEXED ? 3 : srcChannels);
        }

        ByteBuffer result = StbImage.convertChannels(imageData, srcChannels, width, height, desiredChannels, bitDepth == 16);

        if (flipVertically) {
            result = StbImage.verticalFlip(result, width, height, desiredChannels, bitDepth == 16);
        }

        return new StbImageResult(result, width, height, desiredChannels, desiredChannels, bitDepth == 16, false);
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

    private ByteBuffer decompress(ByteBuffer compressed, int width, int height) {
        Inflater inflater = new Inflater();
        compressed.rewind();
        byte[] input = new byte[compressed.remaining()];
        compressed.get(input);
        inflater.setInput(input);

        int scanlineSize = getScanlineSize(width);
        int totalSize = height * scanlineSize;

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
            byte[] decompressed = baos.toByteArray();

            // Copy to output with correct size
            byte[] finalOutput = new byte[totalSize];
            System.arraycopy(decompressed, 0, finalOutput, 0, Math.min(decompressed.length, totalSize));

            return ByteBuffer.wrap(finalOutput);
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
        ByteBuffer output = allocator.allocate(outSize);

        int scanlineSize = getScanlineSize(width);
        byte[] prevScanline = new byte[scanlineSize];
        byte[] curScanline = new byte[scanlineSize];

        for (int y = 0; y < height; y++) {
            int filterType = data.get() & 0xFF;
            for (int i = 0; i < scanlineSize - 1; i++) {
                curScanline[i] = data.get();
            }

            // Unfilter
            int stride = channels * bytesPerChannel;
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

    private void readBytes(ByteBuffer dest, int length) {
        byte[] bytes = new byte[length];
        buffer.get(pos, bytes);
        pos += length;
        dest.put(bytes);
    }
}
