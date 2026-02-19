package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntFunction;


/**
 * JPEG decoder - baseline (sequential) and progressive DCT decoder, implemented to match stb_image.h behavior closely.
 *
 * Supported:
 * - Baseline (SOF0) and Extended Sequential (SOF1)
 * - Progressive (SOF2)
 * - CMYK (via APP14 marker)
 *
 * Not supported:
 * - Arithmetic coding
 *
 * Notes:
 * - Output matches stb_image's resampling + YCbCr conversion (including the hv_2 filter).
 */
public class JpegDecoder implements StbDecoder {

    private static final int DCTSIZE = 8;
    private static final int FAST_BITS = 9;
    private static final int MARKER_NONE = 0xFF;

    private ByteBuffer buffer;
    private int pos;
    private IntFunction<ByteBuffer> allocator;
    private boolean flipVertically;

    private int width;
    private int height;
    private int components;          // 1, 3, or 4 (CMYK)
    private int rgb;                 // 3 if component ids are 'R','G','B', else 0

    // Progressive JPEG state
    private boolean progressive;
    private int scanStart, scanEnd;  // spectral selection for progressive
    private int succHigh, succLow;   // successive approximation
    private int eobRun;

    // CMYK detection
    private boolean app14;           // Adobe APP14 marker found (CMYK flag)
    private int app14ColorTransform = -1; // 0=CMYK, 2=YCCK

    private int imgHMax, imgVMax;
    private int mcusPerRow, mcusPerCol;
    private int restartInterval;
    private int todo;                // MCUs until next restart (if restartInterval != 0)

    // Quantization tables (stb stores uint16; we store int)
    private final int[][] dequant = new int[4][64];

    // Huffman tables: 0-3 DC, 4-7 AC
    private final HuffmanTable[] huffmanTables = new HuffmanTable[8];

    // Scan order from SOS (component indices)
    private int scanN;
    private final int[] scanOrder = new int[4];

    // DC predictors
    private final int[] dcPred = new int[4];

    // Entropy bit buffer (MSB-first like stb)
    private int codeBuffer;
    private int codeBits;
    private int marker;     // pending marker seen while reading entropy
    private boolean nomore; // stop reading entropy bytes

    // Component storage
    private final Component[] imgComp = new Component[4];

    private static final class Component {
        int id;
        int h, v;       // sampling factors
        int tq;         // quant table index
        int hd, ha;     // huffman table selectors (0..3)
        int x, y;       // component image size (cropped, in pixels)
        int w2, h2;     // stored buffer size (MCU padded), in pixels
        int[] data;     // w2*h2 pixels in [0..255]
        int[] linebuf;  // scratch buffer for resampling, at least width+3
        short[] coeff;  // progressive coefficients (64 * coeffW * coeffH)
        int coeffW, coeffH;
    }

    private static final class HuffmanTable {
        // fast lookup maps prefix to symbol-index; 255 = slow path
        final byte[] fast = new byte[1 << FAST_BITS];

        // canonical codes per symbol-index
        final short[] code = new short[256];

        // symbols per symbol-index
        final byte[] values = new byte[256];

        // code size (bits) per symbol-index; size[256] sentinel
        final byte[] size = new byte[257];

        // maxcode thresholds (unsigned-ish, use long)
        final long[] maxcode = new long[18];

        // delta for translating prefix -> symbol-index
        final int[] delta = new int[17];
    }

    private static final class Row {
        final int[] a;
        final int off;
        Row(int[] a, int off) { this.a = a; this.off = off; }
    }

    private interface ResampleRowFunc {
        Row resample(int[] out, int[] inNear, int nearOff, int[] inFar, int farOff, int w, int hs);
    }

    private static final class Resample {
        int hs, vs;
        int ystep;
        int wLores;
        int ypos;
        int line0Off;
        int line1Off;
        ResampleRowFunc func;
    }

    /**
     * Tests whether the input starts with JPEG SOI marker bytes.
     *
     * @param buffer source bytes
     * @return true for JPEG signature
     */
    public static boolean isJpeg(ByteBuffer buffer) {
        if (buffer.remaining() < 2) return false;
        buffer = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        int b0 = buffer.get() & 0xFF;
        int b1 = buffer.get() & 0xFF;
        return b0 == 0xFF && b1 == 0xD8;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }
    
    /**
     * Creates a JPEG decoder instance.
     *
     * @param src source data
     * @param allocator output allocator
     * @param flipVertically true to vertically flip decoded output
     */
    public JpegDecoder(ByteBuffer src, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        // Respect caller's position/limit by slicing
        this.buffer = src.duplicate().order(ByteOrder.BIG_ENDIAN);
        this.allocator = allocator ;
        this.flipVertically = flipVertically;
        this.pos = 0;
        this.marker = MARKER_NONE;
        for (int i = 0; i < imgComp.length; i++) imgComp[i] = new Component();
    }

    // ---------------------------
    // Info-only parse (SOF scan)
    // ---------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageInfo info() {
        try {
            pos = 0;
            marker = MARKER_NONE;
            if (read8() != 0xFF || read8() != 0xD8) return null;

            while (pos < buffer.limit()) {
                int m = findMarker();
                if (m < 0) return null;

                if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7)) continue;
                if (m == 0xD9) return null;
                if (m == 0xDA) return null;

                int len = read16BE();
                int dataStart = pos;

                if ((m >= 0xC0 && m <= 0xCF) && (m != 0xC4 && m != 0xC8 && m != 0xCC)) {
                    int Lf = len;
                    if (Lf < 11) return null;
                    int p = read8();
                    if (p != 8) return null;
                    int h = read16BE();
                    int w = read16BE();
                    int c = read8();
                    if (w <= 0 || h <= 0) return null;
                    // Support 1, 3, or 4 components (4 = CMYK)
                    if (c != 1 && c != 3 && c != 4) return null;
                    // skip rest of SOF
                    for (int i = 0; i < c; i++) {
                        read8(); read8(); read8();
                    }
                    return new StbImageInfo(w, h, c, false, StbImageInfo.ImageFormat.JPEG);
                }

                pos = dataStart + len - 2;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    // ---------------------------
    // Full decode
    // ---------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        parseHeaders(); // fills width/height/components + tables + allocates component buffers + parses SOS

        // stb behavior: if desiredChannels==0 => native channels. otherwise exactly desiredChannels.
        int nativeChannels = components;
        int outChannels = (desiredChannels == 0) ? nativeChannels : desiredChannels;
        if (outChannels < 1 || outChannels > 4) {
            throw new StbFailureException("Bad desired channels: " + outChannels);
        }

        // For progressive, scans were already decoded in parseHeaders()
        // For baseline, decode the single scan now
        if (!progressive) {
            decodeScan();
        } else {
            finishProgressive();
        }

        // stb load_jpeg_image: decode_n == 1 when source is 3 and requested < 3
        // For CMYK (4 components), we decode all 4 but output RGB (3 channels)
        int decodeN = (components == 3 && outChannels < 3) ? 1 : components;
        if (components == 4 && app14) {
            // CMYK: decode all 4 components for conversion
            decodeN = 4;
        }

        // Setup resamplers for components we need
        Resample[] res = new Resample[decodeN];
        for (int i = 0; i < decodeN; i++) {
            res[i] = makeResample(imgComp[i]);
        }

        ByteBuffer output = allocator.apply(width * height * outChannels);

        // Main scanline loop
        for (int y = 0; y < height; y++) {
            Row[] coutput = new Row[decodeN];
            for (int k = 0; k < decodeN; k++) {
                Component c = imgComp[k];
                Resample r = res[k];

                boolean yBot = r.ystep >= (r.vs >> 1);
                int nearOff = yBot ? r.line1Off : r.line0Off;
                int farOff  = yBot ? r.line0Off : r.line1Off;

                coutput[k] = r.func.resample(c.linebuf, c.data, nearOff, c.data, farOff, r.wLores, r.hs);

                if (++r.ystep >= r.vs) {
                    r.ystep = 0;
                    r.line0Off = r.line1Off;
                    if (++r.ypos < c.y) {
                        r.line1Off += c.w2;
                    }
                }
            }

            int outRowOff = y * width * outChannels;

            if (components == 1) {
                // grayscale source
                Row yrow = coutput[0];
                for (int x = 0; x < width; x++) {
                    int g = yrow.a[yrow.off + x];
                    putPixel(output, outRowOff, x, outChannels, g, g, g);
                }
            } else {
                if (outChannels < 3) {
                    // grayscale request from YCbCr: stb uses Y only
                    Row yrow = coutput[0];
                    for (int x = 0; x < width; x++) {
                        int g = yrow.a[yrow.off + x];
                        putGray(output, outRowOff, x, outChannels, g);
                    }
                } else if (rgb == 3) {
                    // JPEG stores RGB directly
                    Row rrow = coutput[0];
                    Row grow = coutput[1];
                    Row brow = coutput[2];
                    for (int x = 0; x < width; x++) {
                        int r8 = rrow.a[rrow.off + x];
                        int g8 = grow.a[grow.off + x];
                        int b8 = brow.a[brow.off + x];
                        putPixel(output, outRowOff, x, outChannels, r8, g8, b8);
                    }
                } else if (components == 4 && app14) {
                    // Adobe APP14 CMYK/YCCK
                    // Y, Cb, Cr, K stored in components 0, 1, 2, 3
                    Row yrow  = coutput[0];
                    Row cbrow = coutput[1];
                    Row crrow = coutput[2];
                    Row krow  = coutput[3];
                    for (int x = 0; x < width; x++) {
                        int Y  = yrow.a[yrow.off + x];
                        int Cb = cbrow.a[cbrow.off + x];
                        int Cr = crrow.a[crrow.off + x];
                        int K  = krow.a[krow.off + x];
                        int rgbPacked = (app14ColorTransform == 0)
                            ? cmykToRgbPacked(Y, Cb, Cr, K)
                            : ycckToRgbPacked(Y, Cb, Cr, K);
                        int r8 = (rgbPacked >>> 16) & 0xFF;
                        int g8 = (rgbPacked >>> 8) & 0xFF;
                        int b8 = rgbPacked & 0xFF;
                        putPixel(output, outRowOff, x, outChannels, r8, g8, b8);
                    }
                } else {
                    // Convert YCbCr -> RGB using stb's fixed-point kernel
                    Row yrow  = coutput[0];
                    Row cbrow = coutput[1];
                    Row crrow = coutput[2];
                    for (int x = 0; x < width; x++) {
                        int Y  = yrow.a[yrow.off + x];
                        int Cb = cbrow.a[cbrow.off + x];
                        int Cr = crrow.a[crrow.off + x];
                        int rgbPacked = ycbcrToRgbPacked(Y, Cb, Cr);
                        int r8 = (rgbPacked >>> 16) & 0xFF;
                        int g8 = (rgbPacked >>> 8) & 0xFF;
                        int b8 = rgbPacked & 0xFF;
                        putPixel(output, outRowOff, x, outChannels, r8, g8, b8);
                    }
                }
            }
        }

        if (flipVertically) {
            output = StbImage.verticalFlip(getAllocator(),output, width, height, outChannels, false);
        }

        output.limit(width * height * outChannels);
        return new StbImageResult(output, width, height, outChannels, desiredChannels, false, false);
    }

    private void putPixel(ByteBuffer out, int rowOff, int x, int outChannels, int r, int g, int b) {
        int p = rowOff + x * outChannels;
        switch (outChannels) {
            case 3:
                out.put(p, (byte) r);
                out.put(p + 1, (byte) g);
                out.put(p + 2, (byte) b);
                break;
            case 4:
                out.put(p, (byte) r);
                out.put(p + 1, (byte) g);
                out.put(p + 2, (byte) b);
                out.put(p + 3, (byte) 255);
                break;
            case 1:
                out.put(p, (byte) r);
                break;
            case 2:
                out.put(p, (byte) r);
                out.put(p + 1, (byte) 255);
                break;
            default:
                throw new StbFailureException("Bad outChannels");
        }
    }

    private void putGray(ByteBuffer out, int rowOff, int x, int outChannels, int g) {
        int p = rowOff + x * outChannels;
        if (outChannels == 1) {
            out.put(p, (byte) g);
        } else if (outChannels == 2) {
            out.put(p, (byte) g);
            out.put(p + 1, (byte) 255);
        } else {
            // not reached
            out.put(p, (byte) g);
        }
    }

    // ---------------------------
    // Header parsing
    // ---------------------------

    private void parseHeaders() {
        pos = 0;
        if (read8() != 0xFF || read8() != 0xD8) {
            throw new StbFailureException("Not a JPEG");
        }

        restartInterval = 0;
        width = height = components = 0;
        rgb = 0;
        marker = MARKER_NONE;

        while (pos < buffer.limit()) {
            int m = findMarker();
            if (m < 0) throw new StbFailureException("Expected marker");

            // standalone markers
            if (m == 0xD8 || (m >= 0xD0 && m <= 0xD7)) continue;
            if (m == 0xD9) break; // EOI

            int len = read16BE();
            int dataStart = pos;

            switch (m) {
                case 0xC0: // baseline
                case 0xC1: // extended sequential
                case 0xC2: // progressive
                    parseSOF(len, m == 0xC2);
                    break;

                case 0xDB:
                    parseDQT(len);
                    break;

                case 0xC4:
                    parseDHT(len);
                    break;

                case 0xDD:
                    parseDRI(len);
                    break;

                case 0xDA: // SOS
                    if (components == 0) throw new StbFailureException("Missing SOF before SOS");
                    parseSOS(len);

                    // For baseline, single scan - done
                    // For progressive, continue to find more scans
                    if (!progressive) {
                        return;
                    }
                    // For progressive, decode this scan and continue
                    decodeScan();
                    continue;

                case 0xEE: // APP14 (Adobe)
                    // Check for CMYK marker
                    parseAPP14(len);
                    break;

                default:
                    // skip other segments (APPx/COM/etc.)
                    break;
            }

            pos = dataStart + len - 2;
        }

        // Progressive: we should have processed at least one scan
        // Baseline: if we get here without returning, error
        if (!progressive) {
            throw new StbFailureException("No SOS");
        }
    }

    /**
     * Correct JPEG marker scanner:
     * - find 0xFF
     * - skip fill 0xFF bytes
     * - skip stuffed 0x00
     * - return marker byte
     */
    private int findMarker() {
        if (marker != MARKER_NONE) {
            int m = marker;
            marker = MARKER_NONE;
            return m;
        }
        int limit = buffer.limit();
        while (pos < limit) {
            while (pos < limit && (buffer.get(pos) & 0xFF) != 0xFF) pos++;
            if (pos >= limit) return -1;

            while (pos < limit && (buffer.get(pos) & 0xFF) == 0xFF) pos++;
            if (pos >= limit) return -1;

            int m = buffer.get(pos) & 0xFF;
            pos++;

            if (m == 0x00) {
                // stuffed byte (shouldn't appear in headers, but be robust)
                continue;
            }
            return m;
        }
        return -1;
    }

    private void parseDRI(int len) {
        if (len != 4) throw new StbFailureException("Bad DRI len");
        restartInterval = read16BE();
    }

    private void parseSOF(int len, boolean isProgressive) {
        int Lf = len;
        if (Lf < 11) throw new StbFailureException("Bad SOF len");
        int p = read8();
        if (p != 8) throw new StbFailureException("Only 8-bit JPEG supported");

        height = read16BE();
        if (height == 0) throw new StbFailureException("Delayed height not supported");
        width = read16BE();
        if (width == 0) throw new StbFailureException("0 width");

        components = read8();
        // Support 1, 3, or 4 components (4 = CMYK)
        if (components != 1 && components != 3 && components != 4) {
            throw new StbFailureException("Bad component count: " + components);
        }

        progressive = isProgressive;

        StbImage.validateDimensions(width, height);

        if (Lf != 8 + 3 * components) {
            // Check for CMYK case: Lf = 8 + 3*4 = 20 for CMYK
            if (!(components == 4 && Lf == 20)) {
                throw new StbFailureException("Bad SOF len: " + Lf + " expected " + (8 + 3 * components));
            }
        }

        imgHMax = 1;
        imgVMax = 1;
        rgb = 0;

        for (int i = 0; i < components; i++) {
            Component c = imgComp[i];
            c.id = read8();

            // stb's component id validation + RGB detection
            // For CMYK (components=4), IDs 1-4 are valid
            if (components == 4) {
                // CMYK: IDs 1-4 are valid
                if (c.id < 1 || c.id > 4) {
                    // Could be RGB order - check for 'R','G','B','C','M','Y','K'
                    if (c.id != 'R' && c.id != 'G' && c.id != 'B' &&
                        c.id != 'C' && c.id != 'M' && c.id != 'Y' && c.id != 'K') {
                        throw new StbFailureException("Bad component ID: " + c.id);
                    }
                }
            } else {
                // Normal RGB/Grayscale
                if (c.id != i + 1) {
                    if (c.id != i) {
                        int expect = (i == 0) ? 'R' : (i == 1) ? 'G' : 'B';
                        if (c.id != expect) throw new StbFailureException("Bad component ID");
                        ++rgb;
                    }
                }
            }

            int samp = read8();
            c.h = (samp >> 4) & 0xF;
            c.v = samp & 0xF;
            if (c.h < 1 || c.h > 4 || c.v < 1 || c.v > 4) throw new StbFailureException("Bad sampling factors");

            imgHMax = Math.max(imgHMax, c.h);
            imgVMax = Math.max(imgVMax, c.v);

            c.tq = read8();
            if (c.tq > 3) throw new StbFailureException("Bad quant table");
        }

        // MCU grid (padded)
        int mcuW = imgHMax * DCTSIZE;
        int mcuH = imgVMax * DCTSIZE;
        mcusPerRow = (width + mcuW - 1) / mcuW;
        mcusPerCol = (height + mcuH - 1) / mcuH;

        // Allocate component buffers like stb
        for (int i = 0; i < components; i++) {
            Component c = imgComp[i];
            c.x = (width * c.h + imgHMax - 1) / imgHMax;
            c.y = (height * c.v + imgVMax - 1) / imgVMax;
            c.w2 = mcusPerRow * c.h * DCTSIZE;
            c.h2 = mcusPerCol * c.v * DCTSIZE;
            c.data = new int[c.w2 * c.h2];
            c.linebuf = new int[width + 3];
            // Progressive coefficient grid is MCU-padded, like stb's coeff_w/coeff_h.
            c.coeffW = c.w2 / 8;
            c.coeffH = c.h2 / 8;
            c.coeff = progressive ? new short[64 * c.coeffW * c.coeffH] : null;
            c.hd = c.ha = 0;
        }
    }

    /**
     * Parse APP14 (Adobe) marker for CMYK detection
     */
    private void parseAPP14(int len) {
        // APP14 has: "Adobe\0" + version (2 bytes) + flags0 (2 bytes) + flags1 (2 bytes) + color transform (1 byte)
        if (len >= 14) {
            // Check for "Adobe" identifier
            boolean isAdobe = true;
            String expected = "Adobe";
            for (int i = 0; i < 5; i++) {
                if ((char)read8() != expected.charAt(i)) {
                    isAdobe = false;
                    break;
                }
            }
            if (isAdobe) {
                // Skip version (2), flags0 (2), flags1 (2)
                pos += 6;
                int transform = read8();
                // Transform: 0 = RGB, 1 = YCbCr, 2 = YCCK
                if (transform == 2 || transform == 0) {
                    app14 = true;
                    app14ColorTransform = transform;
                }
            }
        }
    }

    private void parseDQT(int len) {
        int L = len - 2;
        while (L > 0) {
            int q = read8();
            int p = (q >> 4) & 0xF;
            int t = q & 0xF;
            if (p != 0) throw new StbFailureException("Bad DQT type");
            if (t > 3) throw new StbFailureException("Bad DQT table");

            for (int i = 0; i < 64; i++) {
                dequant[t][dezigzag[i]] = read8();
            }
            L -= 65;
        }
        if (L != 0) throw new StbFailureException("Bad DQT len");
    }

    private void parseDHT(int len) {
        int L = len - 2;
        while (L > 0) {
            int q = read8();
            int tc = (q >> 4) & 0xF; // 0=DC, 1=AC
            int th = q & 0xF;
            if (tc > 1 || th > 3) throw new StbFailureException("Bad DHT header");

            int[] sizes = new int[16];
            int n = 0;
            for (int i = 0; i < 16; i++) {
                sizes[i] = read8();
                n += sizes[i];
            }
            L -= 17;
            if (n > 256) throw new StbFailureException("Bad DHT size");

            int tableIdx = (tc == 0) ? th : th + 4;
            HuffmanTable h = huffmanTables[tableIdx];
            if (h == null) h = (huffmanTables[tableIdx] = new HuffmanTable());

            if (!buildHuffman(h, sizes)) throw new StbFailureException("Corrupt Huffman");

            for (int i = 0; i < n; i++) {
                h.values[i] = (byte) read8();
            }
            L -= n;
        }
        if (L != 0) throw new StbFailureException("Bad DHT len");
    }

    private void parseSOS(int len) {
        int Ls = len;
        scanN = read8();
        if (scanN < 1 || scanN > 4 || scanN > components) throw new StbFailureException("Bad SOS component count");
        if (Ls != 6 + 2 * scanN) throw new StbFailureException("Bad SOS len");

        for (int i = 0; i < scanN; i++) {
            int id = read8();
            int q = read8();

            int which = -1;
            for (int j = 0; j < components; j++) {
                if (imgComp[j].id == id) { which = j; break; }
            }
            if (which < 0) throw new StbFailureException("Bad SOS component id");

            int hd = (q >> 4) & 0xF;
            int ha = q & 0xF;
            if (hd > 3) throw new StbFailureException("Bad DC huff");
            if (ha > 3) throw new StbFailureException("Bad AC huff");

            imgComp[which].hd = hd;
            imgComp[which].ha = ha;
            scanOrder[i] = which;
        }

        // Spectral selection
        scanStart = read8();
        scanEnd = read8();
        int aa = read8();
        succHigh = (aa >> 4) & 0xF;
        succLow  = aa & 0xF;

        // For baseline, these must be zero
        if (!progressive) {
            if (scanStart != 0) throw new StbFailureException("Bad SOS (specStart)");
            if (succHigh != 0 || succLow != 0) throw new StbFailureException("Bad SOS (succ)");
            scanEnd = 63;
        } else {
            if (scanStart > scanEnd || scanStart > 63 || scanEnd > 63) {
                throw new StbFailureException("Bad SOS (spec)");
            }
            if (succHigh > 13 || succLow > 13) throw new StbFailureException("Bad SOS (succ)");
        }

        // reset entropy and predictors
        codeBuffer = 0;
        codeBits = 0;
        marker = MARKER_NONE;
        nomore = false;
        for (int i = 0; i < 4; i++) dcPred[i] = 0;

        eobRun = 0;
        todo = restartInterval != 0 ? restartInterval : Integer.MAX_VALUE;
    }

    // ---------------------------
    // Entropy decode
    // ---------------------------

    private void decodeScan() {
        if (progressive) {
            decodeProgressiveScan();
        } else {
            // baseline: either interleaved (scanN>1) or single-component scan (scanN==1)
            if (scanN == 1) {
                decodeNonInterleavedScan(scanOrder[0]);
            } else {
                decodeInterleavedScan();
            }
        }
    }

    /**
     * Progressive JPEG decoding.
     * Matches stb_image: store coefficients during scans, IDCT later in finishProgressive().
     */
    private void decodeProgressiveScan() {
        resetEntropy();

        if (scanN == 1) {
            int n = scanOrder[0];
            Component c = imgComp[n];
            int blockW = (c.x + 7) >> 3;
            int blockH = (c.y + 7) >> 3;
            for (int by = 0; by < blockH; by++) {
                for (int bx = 0; bx < blockW; bx++) {
                    int off = 64 * (bx + by * c.coeffW);
                    if (scanStart == 0) {
                        if (!decodeBlockProgDc(c.coeff, off, huffmanTables[c.hd], n)) {
                            throw new StbFailureException("Corrupt JPEG");
                        }
                    } else {
                        if (!decodeBlockProgAc(c.coeff, off, huffmanTables[c.ha + 4])) {
                            throw new StbFailureException("Corrupt JPEG");
                        }
                    }
                    if (--todo <= 0) {
                        if (codeBits < 24) growBufferUnsafe();
                        if (!isRestartMarker(marker)) return;
                        resetEntropy();
                    }
                }
            }
            return;
        }

        // Interleaved progressive scans are DC-only.
        for (int mcuY = 0; mcuY < mcusPerCol; mcuY++) {
            for (int mcuX = 0; mcuX < mcusPerRow; mcuX++) {
                for (int si = 0; si < scanN; si++) {
                    int n = scanOrder[si];
                    Component c = imgComp[n];
                    for (int y = 0; y < c.v; y++) {
                        for (int x = 0; x < c.h; x++) {
                            int x2 = mcuX * c.h + x;
                            int y2 = mcuY * c.v + y;
                            int off = 64 * (x2 + y2 * c.coeffW);
                            if (!decodeBlockProgDc(c.coeff, off, huffmanTables[c.hd], n)) {
                                throw new StbFailureException("Corrupt JPEG");
                            }
                        }
                    }
                }
                if (--todo <= 0) {
                    if (codeBits < 24) growBufferUnsafe();
                    if (!isRestartMarker(marker)) return;
                    resetEntropy();
                }
            }
        }
    }

    private void resetEntropy() {
        codeBits = 0;
        codeBuffer = 0;
        nomore = false;
        marker = MARKER_NONE;
        for (int i = 0; i < 4; i++) dcPred[i] = 0;
        todo = restartInterval != 0 ? restartInterval : Integer.MAX_VALUE;
        eobRun = 0;
    }

    private boolean isRestartMarker(int m) {
        return m >= 0xD0 && m <= 0xD7;
    }

    private boolean decodeBlockProgDc(short[] data, int off, HuffmanTable hdc, int compIdx) {
        if (scanEnd != 0) return false;
        if (codeBits < 16) growBufferUnsafe();

        if (succHigh == 0) {
            for (int i = 0; i < 64; i++) data[off + i] = 0;
            int t = jpegHuffDecodeTable(hdc);
            if (t < 0 || t > 15) return false;
            int diff = t != 0 ? extendReceive(t) : 0;
            dcPred[compIdx] += diff;
            data[off] = (short) (dcPred[compIdx] * (1 << succLow));
        } else {
            if (jpegGetBit() != 0) data[off] += (short) (1 << succLow);
        }
        return true;
    }

    private boolean decodeBlockProgAc(short[] data, int off, HuffmanTable hac) {
        if (scanStart == 0) return false;

        if (succHigh == 0) {
            int shift = succLow;
            if (eobRun != 0) {
                --eobRun;
                return true;
            }

            int k = scanStart;
            while (k <= scanEnd) {
                int rs = jpegHuffDecodeTable(hac);
                if (rs < 0) return false;
                int s = rs & 15;
                int r = rs >> 4;
                if (s == 0) {
                    if (r < 15) {
                        eobRun = 1 << r;
                        if (r != 0) eobRun += jpegGetBits(r);
                        --eobRun;
                        break;
                    }
                    k += 16;
                } else {
                    k += r;
                    if (k > scanEnd) break;
                    int zig = dezigzag[k++];
                    data[off + zig] = (short) (extendReceive(s) * (1 << shift));
                }
            }
            return true;
        }

        short bit = (short) (1 << succLow);
        if (eobRun != 0) {
            --eobRun;
            for (int k = scanStart; k <= scanEnd; ++k) {
                int zig = dezigzag[k];
                short p = data[off + zig];
                if (p != 0 && jpegGetBit() != 0 && (p & bit) == 0) {
                    data[off + zig] = (short) (p > 0 ? p + bit : p - bit);
                }
            }
            return true;
        }

        int k = scanStart;
        while (k <= scanEnd) {
            int rs = jpegHuffDecodeTable(hac);
            if (rs < 0) return false;
            int s = rs & 15;
            int r = rs >> 4;
            if (s == 0) {
                if (r < 15) {
                    eobRun = (1 << r) - 1;
                    if (r != 0) eobRun += jpegGetBits(r);
                    r = 64;
                }
            } else {
                if (s != 1) return false;
                s = jpegGetBit() != 0 ? bit : -bit;
            }

            while (k <= scanEnd) {
                int zig = dezigzag[k++];
                short p = data[off + zig];
                if (p != 0) {
                    if (jpegGetBit() != 0 && (p & bit) == 0) {
                        data[off + zig] = (short) (p > 0 ? p + bit : p - bit);
                    }
                } else {
                    if (r == 0) {
                        data[off + zig] = (short) s;
                        break;
                    }
                    --r;
                }
            }
        }
        return true;
    }

    private void finishProgressive() {
        int[] block = new int[64];
        for (int n = 0; n < components; n++) {
            Component c = imgComp[n];
            if (c.coeff == null) continue;
            int[] deq = dequant[c.tq];
            int blockW = (c.x + 7) >> 3;
            int blockH = (c.y + 7) >> 3;
            for (int by = 0; by < blockH; by++) {
                for (int bx = 0; bx < blockW; bx++) {
                    int off = 64 * (bx + by * c.coeffW);
                    for (int i = 0; i < 64; i++) {
                        block[i] = c.coeff[off + i] * deq[i];
                    }
                    int[] idct = applyIdct(block);
                    storeBlock(c, bx * 8, by * 8, idct);
                }
            }
        }
    }

    private void decodeNonInterleavedScan(int compIdx) {
        Component c = imgComp[compIdx];

        int wBlocks = (c.x + 7) >> 3;
        int hBlocks = (c.y + 7) >> 3;

        int[] coeff = new int[64];

        for (int j = 0; j < hBlocks; j++) {
            for (int i = 0; i < wBlocks; i++) {
                decodeBlock(coeff, compIdx);
                int[] idct = applyIdct(coeff);

                int x2 = i * 8;
                int y2 = j * 8;
                storeBlock(c, x2, y2, idct);

                if (restartInterval != 0) {
                    if (--todo == 0) {
                        processRestart();
                        todo = restartInterval;
                    }
                }
            }
        }
    }

    private void decodeInterleavedScan() {
        int[] coeff = new int[64];

        for (int mcuY = 0; mcuY < mcusPerCol; mcuY++) {
            for (int mcuX = 0; mcuX < mcusPerRow; mcuX++) {
                for (int si = 0; si < scanN; si++) {
                    int compIdx = scanOrder[si];
                    Component c = imgComp[compIdx];

                    for (int by = 0; by < c.v; by++) {
                        for (int bx = 0; bx < c.h; bx++) {
                            decodeBlock(coeff, compIdx);
                            int[] idct = applyIdct(coeff);

                            int x2 = (mcuX * c.h + bx) * 8;
                            int y2 = (mcuY * c.v + by) * 8;
                            storeBlock(c, x2, y2, idct);
                        }
                    }
                }

                if (restartInterval != 0) {
                    if (--todo == 0) {
                        processRestart();
                        todo = restartInterval;
                    }
                }
            }
        }
    }

    private void storeBlock(Component c, int x2, int y2, int[] block8x8) {
        int base = y2 * c.w2 + x2;
        for (int j = 0; j < 8; j++) {
            int rowOff = base + j * c.w2;
            int srcOff = j * 8;
            // block is already clamped 0..255
            for (int i = 0; i < 8; i++) {
                c.data[rowOff + i] = block8x8[srcOff + i];
            }
        }
    }

    // ---------------------------
    // Huffman build/decode (stb-like)
    // ---------------------------

    private boolean buildHuffman(HuffmanTable h, int[] count /* length 16 */) {
        // initialize fast table to 255
        for (int i = 0; i < h.fast.length; i++) h.fast[i] = (byte) 0xFF;

        int i, j;
        int k = 0;

        // build size list for each symbol-index
        for (i = 0; i < 16; i++) {
            for (j = 0; j < count[i]; j++) {
                if (k >= 256) return false;
                h.size[k++] = (byte) (i + 1);
            }
        }
        h.size[k] = 0;

        // compute canonical codes, delta and maxcode thresholds
        int code = 0;
        k = 0;

        for (j = 1; j <= 16; j++) {
            h.delta[j] = k - code;

            if ((h.size[k] & 0xFF) == j) {
                while ((h.size[k] & 0xFF) == j) {
                    h.code[k++] = (short) (code++);
                    if (k >= 256) break;
                }
                if (code - 1 >= (1 << j)) return false;
            }

            h.maxcode[j] = ((long) code) << (16 - j);
            code <<= 1;
        }
        h.maxcode[17] = 0xFFFFFFFFL;

        // build fast lookup
        for (i = 0; i < k; i++) {
            int s = h.size[i] & 0xFF;
            if (s <= FAST_BITS) {
                int c = (h.code[i] & 0xFFFF) << (FAST_BITS - s);
                int m = 1 << (FAST_BITS - s);
                for (j = 0; j < m; j++) {
                    h.fast[c + j] = (byte) i; // symbol-index
                }
            }
        }
        return true;
    }

    // masks 0..16
    private static final int[] bmask = {
        0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535
    };

    private void growBufferUnsafe() {
        // stb: keep at least 24 bits in the buffer
        do {
            int b;
            if (nomore) {
                b = 0;
            } else if (pos >= buffer.limit()) {
                nomore = true;
                b = 0;
            } else {
                b = buffer.get(pos++) & 0xFF;
            }

            if (!nomore && b == 0xFF) {
                int c;
                if (pos >= buffer.limit()) {
                    nomore = true;
                    c = 0;
                } else {
                    c = buffer.get(pos++) & 0xFF;
                }
                if (c != 0) {
                    marker = c;
                    nomore = true;
                    return;
                }
                // stuffed 0xFF => keep b = 0xFF
            }

            codeBuffer |= (b << (24 - codeBits));
            codeBits += 8;
        } while (codeBits <= 24);
    }

    private int jpegHuffDecode(int tableIdx) {
        HuffmanTable h = huffmanTables[tableIdx];
        if (h == null) return -1;

        if (codeBits < 16) growBufferUnsafe();

        int c = (codeBuffer >>> (32 - FAST_BITS)) & ((1 << FAST_BITS) - 1);
        int k = h.fast[c] & 0xFF;

        if (k < 255) {
            int s = h.size[k] & 0xFF;
            if (s > codeBits) return -1;
            codeBuffer <<= s;
            codeBits -= s;
            return h.values[k] & 0xFF;
        }

        int temp = codeBuffer >>> 16;
        int s;
        for (s = FAST_BITS + 1; s <= 16; s++) {
            if (((long) temp) < h.maxcode[s]) break;
        }
        if (s == 17) {
            codeBits -= 16;
            return -1;
        }
        if (s > codeBits) return -1;

        int symIndex = ((codeBuffer >>> (32 - s)) & bmask[s]) + h.delta[s];
        codeBuffer <<= s;
        codeBits -= s;

        if (symIndex < 0 || symIndex >= 256) return -1;
        return h.values[symIndex] & 0xFF;
    }

    private int extendReceive(int n) {
        if (n == 0) return 0;
        if (codeBits < n) growBufferUnsafe();

        int k = (codeBuffer >>> (32 - n)) & bmask[n];
        codeBuffer <<= n;
        codeBits -= n;

        int m = 1 << (n - 1);
        if (k < m) return (-1 << n) + k + 1;
        return k;
    }

    // Zigzag order (stb_image.h stbi__jpeg_dezigzag)
    private static final int[] dezigzag = {
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63
    };

    // ---------------------------
    // Restart marker handling
    // ---------------------------

    private void processRestart() {
        // align to next byte boundary
        int discard = codeBits & 7;
        if (discard != 0) {
            codeBuffer <<= discard;
            codeBits -= discard;
        }

        int m = marker;
        marker = MARKER_NONE;

        if (m == MARKER_NONE) {
            // scan raw stream for 0xFF then marker
            int c;
            do {
                if (pos >= buffer.limit()) throw new StbFailureException("Missing restart marker");
                c = buffer.get(pos++) & 0xFF;
            } while (c != 0xFF);

            do {
                if (pos >= buffer.limit()) throw new StbFailureException("Missing restart marker");
                c = buffer.get(pos++) & 0xFF;
            } while (c == 0xFF);

            m = c;
        }

        if (m < 0xD0 || m > 0xD7) {
            if (m == 0xD9) {
                // Some streams terminate without the expected final restart marker.
                nomore = true;
                return;
            }
            throw new StbFailureException("Bad restart marker: 0x" + Integer.toHexString(m));
        }

        // reset predictors and entropy
        for (int i = 0; i < 4; i++) dcPred[i] = 0;
        codeBuffer = 0;
        codeBits = 0;
        nomore = false;
        eobRun = 0;
    }

    private int jpegHuffDecodeTable(HuffmanTable h) {
        if (h == null) return -1;
        if (codeBits < 16) growBufferUnsafe();

        int c = (codeBuffer >>> (32 - FAST_BITS)) & ((1 << FAST_BITS) - 1);
        int k = h.fast[c] & 0xFF;
        if (k < 255) {
            int s = h.size[k] & 0xFF;
            if (s > codeBits) return -1;
            codeBuffer <<= s;
            codeBits -= s;
            return h.values[k] & 0xFF;
        }

        int temp = codeBuffer >>> 16;
        int s;
        for (s = FAST_BITS + 1; s <= 16; s++) {
            if (((long) temp) < h.maxcode[s]) break;
        }
        if (s == 17 || s > codeBits) return -1;

        int symIndex = ((codeBuffer >>> (32 - s)) & bmask[s]) + h.delta[s];
        if (symIndex < 0 || symIndex >= 256) return -1;
        codeBuffer <<= s;
        codeBits -= s;
        return h.values[symIndex] & 0xFF;
    }

    private int jpegGetBits(int n) {
        if (codeBits < n) growBufferUnsafe();
        if (codeBits < n) return 0;
        int k = (codeBuffer >>> (32 - n)) & bmask[n];
        codeBuffer <<= n;
        codeBits -= n;
        return k;
    }

    private int jpegGetBit() {
        if (codeBits < 1) growBufferUnsafe();
        if (codeBits < 1) return 0;
        int k = codeBuffer;
        codeBuffer <<= 1;
        --codeBits;
        return k & 0x80000000;
    }

    // ---------------------------
    // Block decode (baseline)
    // ---------------------------

    private void decodeBlock(int[] data64, int compIdx) {
        Component c = imgComp[compIdx];

        int dcTable = c.hd;
        int acTable = c.ha + 4;

        if (huffmanTables[dcTable] == null || huffmanTables[acTable] == null) {
            throw new StbFailureException("Missing Huffman table");
        }

        // clear
        for (int i = 0; i < 64; i++) data64[i] = 0;

        int t = jpegHuffDecode(dcTable);
        if (t < 0) {
            if (nomore) return;
            throw new StbFailureException("Bad Huffman (DC)");
        }

        int diff = (t != 0) ? extendReceive(t) : 0;
        dcPred[compIdx] += diff;

        int qt = c.tq;
        data64[0] = dcPred[compIdx] * dequant[qt][0];

        int k = 1;
        while (k < 64) {
            int rs = jpegHuffDecode(acTable);
            if (rs < 0) {
                if (nomore) break;
                throw new StbFailureException("Bad Huffman (AC)");
            }

            int s = rs & 15;
            int r = rs >> 4;

            if (s == 0) {
                if (r == 15) {
                    k += 16;
                    continue;
                }
                break; // EOB
            }

            k += r;
            if (k >= 64) throw new StbFailureException("Bad AC run");

            int zz = dezigzag[k];
            int v = extendReceive(s);
            data64[zz] = v * dequant[qt][zz];
            k++;
        }
    }

    // ---------------------------
    // Resampling (matches stb_image.h)
    // ---------------------------

    private Resample makeResample(Component c) {
        Resample r = new Resample();
        r.hs = imgHMax / c.h;
        r.vs = imgVMax / c.v;
        r.ystep = r.vs >> 1;
        r.wLores = (width + r.hs - 1) / r.hs;
        r.ypos = 0;
        r.line0Off = 0;
        r.line1Off = 0;

        if (r.hs == 1 && r.vs == 1) {
            r.func = RESAMPLE_ROW_1;
        } else if (r.hs == 1 && r.vs == 2) {
            r.func = RESAMPLE_ROW_V_2;
        } else if (r.hs == 2 && r.vs == 1) {
            r.func = RESAMPLE_ROW_H_2;
        } else if (r.hs == 2 && r.vs == 2) {
            r.func = RESAMPLE_ROW_HV_2;
        } else {
            r.func = RESAMPLE_ROW_GENERIC;
        }
        return r;
    }

    // div helpers (stb uses shifts; rounding constants are passed by caller)
    private static int div4(int x) { return x >> 2; }
    private static int div16(int x) { return x >> 4; }

    private static final ResampleRowFunc RESAMPLE_ROW_1 = (out, inNear, nearOff, inFar, farOff, w, hs) ->
        new Row(inNear, nearOff);

    // out[i] = div4(3*inNear[i] + inFar[i] + 2)
    private static final ResampleRowFunc RESAMPLE_ROW_V_2 = (out, inNear, nearOff, inFar, farOff, w, hs) -> {
        for (int i = 0; i < w; i++) {
            int t = 3 * inNear[nearOff + i] + inFar[farOff + i];
            out[i] = div4(t + 2);
        }
        return new Row(out, 0);
    };

    // out has 2*w samples
    private static final ResampleRowFunc RESAMPLE_ROW_H_2 = (out, inNear, nearOff, inFar, farOff, w, hs) -> {
        if (w == 1) {
            int val = inNear[nearOff];
            out[0] = val;
            out[1] = val;
            return new Row(out, 0);
        }
        int t1 = inNear[nearOff + 0];
        out[0] = t1;
        out[1] = div4(3 * t1 + inNear[nearOff + 1] + 2);
        int i;
        for (i = 1; i < w - 1; i++) {
            int t0 = t1;
            t1 = inNear[nearOff + i];
            out[i * 2 + 0] = div4(3 * t1 + t0 + 2);
            out[i * 2 + 1] = div4(3 * t1 + inNear[nearOff + i + 1] + 2);
        }
        out[i * 2 + 0] = div4(3 * t1 + inNear[nearOff + i - 1] + 2);
        out[i * 2 + 1] = t1;
        return new Row(out, 0);
    };

    // out has 2*w samples
    private static final ResampleRowFunc RESAMPLE_ROW_HV_2 = (out, inNear, nearOff, inFar, farOff, w, hs) -> {
        if (w == 1) {
            int t1 = 3 * inNear[nearOff + 0] + inFar[farOff + 0];
            out[0] = div4(t1 + 2);
            out[1] = div4(t1 + 2);
            return new Row(out, 0);
        }
        int t1 = 3 * inNear[nearOff + 0] + inFar[farOff + 0];
        out[0] = div4(t1 + 2);
        int i;
        for (i = 1; i < w; i++) {
            int t0 = t1;
            t1 = 3 * inNear[nearOff + i] + inFar[farOff + i];
            out[i * 2 - 1] = div16(3 * t0 + t1 + 8);
            out[i * 2]     = div16(3 * t1 + t0 + 8);
        }
        out[w * 2 - 1] = div4(t1 + 2);
        return new Row(out, 0);
    };

    private static final ResampleRowFunc RESAMPLE_ROW_GENERIC = (out, inNear, nearOff, inFar, farOff, w, hs) -> {
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < hs; j++) {
                out[i * hs + j] = inNear[nearOff + i];
            }
        }
        return new Row(out, 0);
    };

    // ---------------------------
    // YCbCr->RGB (stb fixed-point)
    // ---------------------------

    // stb__float2fixed(x) = (((int)((x) * 4096.0f + 0.5f)) << 8)
    private static final int FIX_1_40200 = 1470208;
    private static final int FIX_0_71414 = 748800;
    private static final int FIX_0_34414 = 360960;
    private static final int FIX_1_77200 = 1858048;

    private int ycbcrToRgbPacked(int y, int cb, int cr) {
        int yFixed = (y << 20) + (1 << 19);
        int crv = cr - 128;
        int cbv = cb - 128;

        int r = (yFixed + crv * FIX_1_40200) >> 20;
        int g = (yFixed + crv * -FIX_0_71414 + ((cbv * -FIX_0_34414) & 0xffff0000)) >> 20;
        int b = (yFixed + cbv * FIX_1_77200) >> 20;

        r = clamp(r);
        g = clamp(g);
        b = clamp(b);

        return (r << 16) | (g << 8) | b;
    }

    private int blinn8x8(int x, int y) {
        int t = x * y + 128;
        return (t + (t >> 8)) >> 8;
    }

    private int cmykToRgbPacked(int c, int m, int y, int k) {
        int r = blinn8x8(c, k);
        int g = blinn8x8(m, k);
        int b = blinn8x8(y, k);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private int ycckToRgbPacked(int y, int cb, int cr, int k) {
        int rgb = ycbcrToRgbPacked(y, cb, cr);
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = blinn8x8(255 - r, k);
        g = blinn8x8(255 - g, k);
        b = blinn8x8(255 - b, k);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    // ---------------------------
    // IDCT (stb-like)
    // ---------------------------

    private static final long F2F_0_5411961 = 2217L;
    private static final long F2F_1_847759065 = -7568L;
    private static final long F2F_0_765366865 = 3136L;
    private static final long F2F_1_175875602 = 4816L;
    private static final long F2F_0_298631336 = 1223L;
    private static final long F2F_2_053119869 = 8410L;
    private static final long F2F_3_072711026 = 12586L;
    private static final long F2F_1_501321110 = 6153L;
    private static final long F2F_MINUS_0_899976223 = -3686L;
    private static final long F2F_MINUS_2_562915447 = -10503L;
    private static final long F2F_MINUS_1_961570560 = -8038L;
    private static final long F2F_MINUS_0_390180644 = -1599L;

    private int[] applyIdct(int[] coeff) {
        int[] output = new int[64];
        long[] v = new long[64];

        for (int i = 0; i < 8; i++) {
            if (coeff[i + 8] == 0 && coeff[i + 16] == 0 && coeff[i + 24] == 0 &&
                coeff[i + 32] == 0 && coeff[i + 40] == 0 && coeff[i + 48] == 0 && coeff[i + 56] == 0) {
                long dcterm = (long) coeff[i] * 4;
                v[i + 0] = v[i + 8] = v[i + 16] = v[i + 24] = v[i + 32] = v[i + 40] = v[i + 48] = v[i + 56] = dcterm;
            } else {
                long s0 = coeff[i];
                long s1 = coeff[i + 8];
                long s2 = coeff[i + 16];
                long s3 = coeff[i + 24];
                long s4 = coeff[i + 32];
                long s5 = coeff[i + 40];
                long s6 = coeff[i + 48];
                long s7 = coeff[i + 56];

                long t0, t1, t2, t3, p1, p2, p3, p4, p5, x0, x1, x2, x3;

                p2 = s2;
                p3 = s6;
                p1 = (p2 + p3) * F2F_0_5411961;
                t2 = p1 + p3 * F2F_1_847759065;
                t3 = p1 + p2 * F2F_0_765366865;
                p2 = s0;
                p3 = s4;
                t0 = (p2 + p3) * 4096;
                t1 = (p2 - p3) * 4096;
                x0 = t0 + t3;
                x3 = t0 - t3;
                x1 = t1 + t2;
                x2 = t1 - t2;

                t0 = s7;
                t1 = s5;
                t2 = s3;
                t3 = s1;
                p3 = t0 + t2;
                p4 = t1 + t3;
                p1 = t0 + t3;
                p2 = t1 + t2;
                p5 = (p3 + p4) * F2F_1_175875602;
                t0 = t0 * F2F_0_298631336;
                t1 = t1 * F2F_2_053119869;
                t2 = t2 * F2F_3_072711026;
                t3 = t3 * F2F_1_501321110;
                p1 = p5 + p1 * F2F_MINUS_0_899976223;
                p2 = p5 + p2 * F2F_MINUS_2_562915447;
                p3 = p3 * F2F_MINUS_1_961570560;
                p4 = p4 * F2F_MINUS_0_390180644;
                t3 += p1 + p4;
                t2 += p2 + p3;
                t1 += p2 + p4;
                t0 += p1 + p3;

                x0 += 512;
                x1 += 512;
                x2 += 512;
                x3 += 512;

                v[i + 0] = (x0 + t3) >> 10;
                v[i + 56] = (x0 - t3) >> 10;
                v[i + 8] = (x1 + t2) >> 10;
                v[i + 48] = (x1 - t2) >> 10;
                v[i + 16] = (x2 + t1) >> 10;
                v[i + 40] = (x2 - t1) >> 10;
                v[i + 24] = (x3 + t0) >> 10;
                v[i + 32] = (x3 - t0) >> 10;
            }
        }

        for (int row = 0; row < 8; row++) {
            int base = row * 8;
            long s0 = v[base + 0];
            long s1 = v[base + 1];
            long s2 = v[base + 2];
            long s3 = v[base + 3];
            long s4 = v[base + 4];
            long s5 = v[base + 5];
            long s6 = v[base + 6];
            long s7 = v[base + 7];

            long t0, t1, t2, t3, p1, p2, p3, p4, p5, x0, x1, x2, x3;

            p2 = s2;
            p3 = s6;
            p1 = (p2 + p3) * F2F_0_5411961;
            t2 = p1 + p3 * F2F_1_847759065;
            t3 = p1 + p2 * F2F_0_765366865;
            p2 = s0;
            p3 = s4;
            t0 = (p2 + p3) * 4096;
            t1 = (p2 - p3) * 4096;
            x0 = t0 + t3;
            x3 = t0 - t3;
            x1 = t1 + t2;
            x2 = t1 - t2;

            t0 = s7;
            t1 = s5;
            t2 = s3;
            t3 = s1;
            p3 = t0 + t2;
            p4 = t1 + t3;
            p1 = t0 + t3;
            p2 = t1 + t2;
            p5 = (p3 + p4) * F2F_1_175875602;
            t0 = t0 * F2F_0_298631336;
            t1 = t1 * F2F_2_053119869;
            t2 = t2 * F2F_3_072711026;
            t3 = t3 * F2F_1_501321110;
            p1 = p5 + p1 * F2F_MINUS_0_899976223;
            p2 = p5 + p2 * F2F_MINUS_2_562915447;
            p3 = p3 * F2F_MINUS_1_961570560;
            p4 = p4 * F2F_MINUS_0_390180644;
            t3 += p1 + p4;
            t2 += p2 + p3;
            t1 += p2 + p4;
            t0 += p1 + p3;

            long bias = 65536L + (128L << 17);
            x0 += bias;
            x1 += bias;
            x2 += bias;
            x3 += bias;

            output[base + 0] = clamp((int) ((x0 + t3) >> 17));
            output[base + 7] = clamp((int) ((x0 - t3) >> 17));
            output[base + 1] = clamp((int) ((x1 + t2) >> 17));
            output[base + 6] = clamp((int) ((x1 - t2) >> 17));
            output[base + 2] = clamp((int) ((x2 + t1) >> 17));
            output[base + 5] = clamp((int) ((x2 - t1) >> 17));
            output[base + 3] = clamp((int) ((x3 + t0) >> 17));
            output[base + 4] = clamp((int) ((x3 - t0) >> 17));
        }

        return output;
    }

    private static int clamp(int val) {
        if (val < 0) return 0;
        if (val > 255) return 255;
        return val;
    }

    // ---------------------------
    // Byte reading helpers
    // ---------------------------

    private int read8() {
        if (pos >= buffer.limit()) throw new StbFailureException("Unexpected EOF");
        return buffer.get(pos++) & 0xFF;
    }

    private int read16BE() {
        int a = read8();
        int b = read8();
        return (a << 8) | b;
    }
}
