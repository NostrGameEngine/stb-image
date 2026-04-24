package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * GIF decoder.
 *
 * Supports regular stbi_load-like first-frame decode through {@link #load(int)},
 * streaming frame-by-frame decode through {@link #loadNextFrame(int)}, and
 * full materialization through {@link #loadAllFrames(int)}.
 */
public class GifDecoder implements StbDecoder {
    private static final String GIF87_SIGNATURE = "GIF87a";
    private static final String GIF89_SIGNATURE = "GIF89a";

    private static final int EXT_GRAPHICS_CONTROL = 0xF9;
    private static final int EXT_IMAGE = 0x2C;
    private static final int EXT_INTRODUCER = 0x21;
    private static final int TRAILER = 0x3B;

    private final ByteBuffer buffer;
    private int pos;
    private final IntFunction<ByteBuffer> allocator;
    private final boolean flipVertically;

    private int width;
    private int height;
    private int globalColorTableSize;
    private byte[] globalColorTable;
    private int backgroundColorIndex;
    private boolean hasGlobalColorTable;

    private boolean headerInitialized;
    private boolean streamEnded;
    private int frameCount = -1;
    private int nextFrameIndex;
    private int lastFrameDelayMs;

    private byte[] canvas;
    private byte[] background;
    private byte[] history;
    private byte[] prevFrame;
    private byte[] prevPrevFrame;
    private int previousDisposal;
    private boolean firstFrame;

    private int gceDisposal;
    private int gceTransparent;
    private int gceDelayMs;

    // Incremental cache: each decoded frame is retained for fast replay/seek.
    private final List<GifFrame> frames = new ArrayList<>();

    // Keep untouched first-frame pixels transparent for viewer-friendly rendering.
    private boolean fillFirstFrameBackground = false;

    private static final class ImageBlock {
        int left;
        int top;
        int width;
        int height;
        boolean interlaced;
        int lzwMinCodeSize;
        byte[] lzwData;
        byte[] localColorTable;
    }

    private static final class GifFrame {
        final int delayMs;
        final StbImageResult rgba;
        final Map<Integer, StbImageResult> variants = new HashMap<>();

        GifFrame(StbImageResult rgbaResult, int delayMs) {
            this.rgba = rgbaResult;
            this.delayMs = delayMs;
        }
    }

    /**
     * Tests whether the source starts with a GIF87a/GIF89a signature.
     *
     * @param buffer source bytes
     * @return true if the signature indicates GIF
     */
    public static boolean isGif(ByteBuffer buffer) {
        if (buffer.remaining() < 6) return false;
        ByteBuffer b = buffer.duplicate();
        byte[] sig = new byte[6];
        b.get(sig);
        return sig[0] == 'G' && sig[1] == 'I' && sig[2] == 'F' && sig[3] == '8'
            && (sig[4] == '7' || sig[4] == '9') && sig[5] == 'a';
    }

    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    /**
     * Creates a GIF decoder instance.
     *
     * @param buffer source data
     * @param allocator output allocator
     * @param flipVertically true to flip decoded rows
     */
    public GifDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        StbLimits.lock();
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    @Override
    public StbImageInfo info() {
        try {
            int oldPos = pos;
            pos = 0;

            String signature = readString(6);
            if (!signature.equals(GIF87_SIGNATURE) && !signature.equals(GIF89_SIGNATURE)) {
                pos = oldPos;
                return null;
            }

            int w = readU16LE();
            int h = readU16LE();
            pos = oldPos;
            return new StbImageInfo(w, h, 4, false, StbImageInfo.ImageFormat.GIF, getFrameCount());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes and returns the first frame (stb-style load behavior).
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        ensureHeaderInitialized();

        if (frames.isEmpty()) {
            GifFrame first = decodeNextFrameInternal();
            if (first == null) {
                throw new StbFailureException("No image data found");
            }
            addFrame(first);
        }

        GifFrame frame = frames.get(0);
        lastFrameDelayMs = frame.delayMs;
        nextFrameIndex = 1;
        return toResult(frame, 0, desiredChannels);
    }

    /**
     * Loads the next GIF frame in streaming mode. Once the trailer is reached,
     * already decoded frames are replayed in a loop.
     */
    @Override
    public StbImageResult loadNextFrame(int desiredChannels) {
        ensureHeaderInitialized();

        if (streamEnded && !frames.isEmpty() && nextFrameIndex >= frames.size()) {
            nextFrameIndex = 0;
        }

        if (nextFrameIndex >= frames.size()) {
            GifFrame decoded = decodeNextFrameInternal();
            if (decoded != null) {
                addFrame(decoded);
            } else {
                if (frames.isEmpty()) {
                    throw new StbFailureException("No image data found");
                }
                nextFrameIndex = 0;
            }
        }

        GifFrame frame = frames.get(nextFrameIndex);
        lastFrameDelayMs = frame.delayMs;
        StbImageResult out = toResult(frame, nextFrameIndex, desiredChannels);
        nextFrameIndex++;
        if (streamEnded && nextFrameIndex >= frames.size()) {
            nextFrameIndex = 0;
        }
        return out;
    }

    /**
     * Decodes all frames and returns them.
     *
     * @param desiredChannels requested channel count
     * @return all frames in playback order
     */
    @Override
    public List<StbImageResult> loadAllFrames(int desiredChannels) {
        ensureHeaderInitialized();
        while (!streamEnded) {
            GifFrame decoded = decodeNextFrameInternal();
            if (decoded == null) {
                break;
            }
            addFrame(decoded);
        }

        if (frames.isEmpty()) {
            throw new StbFailureException("No image data found");
        }

        List<StbImageResult> out = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            out.add(toResult(frames.get(i), i, desiredChannels));
        }
        return out;
    }

    /**
     * Returns the number of frames in the GIF stream.
     */
    public int getFrameCount() {
        if (frameCount >= 0) {
            return frameCount;
        }
        frameCount = scanFrameCount();
        return frameCount;
    }

    /**
     * Indicates whether the GIF contains more than one frame.
     */
    public boolean isAnimated() {
        return getFrameCount() > 1;
    }

    /**
     * Returns delay in milliseconds for the last frame returned by {@link #load(int)}
     * or {@link #loadNextFrame(int)}.
     */
    public int getLastFrameDelayMs() {
        return lastFrameDelayMs;
    }

    /**
     * Controls whether first-frame untouched pixels are filled using GIF logical
     * screen background color.
     *
     * <p>Enabled matches stb behavior. Disabling is useful for UI preview flows
     * that prefer transparent untouched pixels.</p>
     */
    public void setFillFirstFrameBackground(boolean fillFirstFrameBackground) {
        this.fillFirstFrameBackground = fillFirstFrameBackground;
    }

    private void addFrame(GifFrame frame) {
        frames.add(frame);
    }

    private void ensureHeaderInitialized() {
        if (headerInitialized) return;

        pos = 0;
        String signature = readString(6);
        if (!signature.equals(GIF87_SIGNATURE) && !signature.equals(GIF89_SIGNATURE)) {
            throw new StbFailureException("Not a GIF file");
        }

        width = readU16LE();
        height = readU16LE();
        StbLimits.validateDimensions(width, height);

        int packed = readU8();
        hasGlobalColorTable = (packed & 0x80) != 0;
        globalColorTableSize = 1 << ((packed & 0x7) + 1);
        backgroundColorIndex = readU8();
        readU8(); // pixel aspect ratio

        if (hasGlobalColorTable) {
            globalColorTable = new byte[globalColorTableSize * 3];
            ensureAvailable(globalColorTable.length, "GIF global color table truncated");
            for (int i = 0; i < globalColorTable.length; i++) {
                globalColorTable[i] = (byte) readU8();
            }
        } else {
            globalColorTable = new byte[0];
        }

        int pixelCount = StbLimits.checkedPixelCount(width, height);
        int rgbaSize = StbLimits.checkedImageBufferSize(width, height, 4, 1);
        canvas = new byte[rgbaSize];
        background = new byte[rgbaSize];
        history = new byte[pixelCount];
        prevFrame = null;
        prevPrevFrame = null;

        previousDisposal = 0;
        firstFrame = true;

        gceDisposal = 0;
        gceTransparent = -1;
        gceDelayMs = 0;

        headerInitialized = true;
        streamEnded = false;
        nextFrameIndex = 0;
        lastFrameDelayMs = 0;
    }

    private GifFrame decodeNextFrameInternal() {
        if (streamEnded) {
            return null;
        }

        while (pos < buffer.limit()) {
            int tag = readU8();

            if (tag == EXT_IMAGE) {
                ImageBlock image = readImageBlock();
                applyDisposalFromPreviousFrame();

                System.arraycopy(canvas, 0, background, 0, background.length);
                Arrays.fill(history, (byte) 0);

                byte[] colorTable = image.localColorTable != null ? image.localColorTable : globalColorTable;
                if (colorTable == null || colorTable.length == 0) {
                    throw new StbFailureException("Missing GIF color table");
                }

                drawLzwImage(image, colorTable, gceTransparent, canvas, history);

                if (fillFirstFrameBackground && firstFrame && backgroundColorIndex > 0
                    && globalColorTable.length >= (backgroundColorIndex + 1) * 3) {
                    int bp = backgroundColorIndex * 3;
                    byte br = globalColorTable[bp + 2];
                    byte bg = globalColorTable[bp + 1];
                    byte bb = globalColorTable[bp];
                    int pixelCount = StbLimits.checkedPixelCount(width, height);
                    for (int i = 0; i < pixelCount; i++) {
                        if (history[i] == 0) {
                            int p = i * 4;
                            canvas[p] = br;
                            canvas[p + 1] = bg;
                            canvas[p + 2] = bb;
                            canvas[p + 3] = (byte) 0xFF;
                        }
                    }
                }

                byte[] frameCopy = canvas.clone();
                ByteBuffer rgbaData = allocator.apply(frameCopy.length);
                for (int i = 0; i < frameCopy.length; i++) {
                    rgbaData.put(i, frameCopy[i]);
                }
                rgbaData.limit(frameCopy.length);
                if (flipVertically) {
                    rgbaData = StbUtils.verticalFlip(getAllocator(), rgbaData, width, height, 4, false);
                }
                StbImageResult rgbaResult = new StbImageResult(rgbaData, width, height, 4, 4, false, false);
                GifFrame frame = new GifFrame(rgbaResult, gceDelayMs);

                prevPrevFrame = prevFrame;
                prevFrame = frameCopy;
                previousDisposal = gceDisposal;
                firstFrame = false;

                return frame;
            }

            if (tag == EXT_INTRODUCER) {
                int ext = readU8();
                if (ext == EXT_GRAPHICS_CONTROL) {
                    int[] gce = readGraphicsControlBlock();
                    gceDisposal = gce[0];
                    gceTransparent = gce[1];
                    gceDelayMs = gce[2];
                } else {
                    skipSubBlocks();
                }
                continue;
            }

            if (tag == TRAILER) {
                streamEnded = true;
                return null;
            }

            throw new StbFailureException("Corrupt GIF stream");
        }

        streamEnded = true;
        return null;
    }

    private void applyDisposalFromPreviousFrame() {
        if (firstFrame) {
            return;
        }

        int pixelCount = StbLimits.checkedPixelCount(width, height);
        int dispose = previousDisposal;
        if (dispose == 3 && prevPrevFrame == null) {
            dispose = 2;
        }

        if (dispose == 3) {
            for (int i = 0; i < pixelCount; i++) {
                if (history[i] != 0) {
                    int p = i * 4;
                    canvas[p] = prevPrevFrame[p];
                    canvas[p + 1] = prevPrevFrame[p + 1];
                    canvas[p + 2] = prevPrevFrame[p + 2];
                    canvas[p + 3] = prevPrevFrame[p + 3];
                }
            }
        } else if (dispose == 2) {
            for (int i = 0; i < pixelCount; i++) {
                if (history[i] != 0) {
                    int p = i * 4;
                    canvas[p] = background[p];
                    canvas[p + 1] = background[p + 1];
                    canvas[p + 2] = background[p + 2];
                    canvas[p + 3] = background[p + 3];
                }
            }
        }
    }

    private int[] readGraphicsControlBlock() {
        int blockSize = readU8();
        if (blockSize != 4) {
            pos += Math.max(0, blockSize);
            if (pos < buffer.limit()) {
                int term = readU8();
                if (term != 0) {
                    while (term != 0 && pos < buffer.limit()) {
                        pos += term;
                        term = readU8();
                    }
                }
            }
            return new int[] {0, -1, 0};
        }

        int packed = readU8();
        int delayTime = readU16LE();
        int transparentColorIndex = readU8();
        readU8();

        int disposal = (packed >> 2) & 0x7;
        boolean hasTransparency = (packed & 0x1) != 0;
        int transparent = hasTransparency ? transparentColorIndex : -1;
        int delayMs = delayTime * 10;
        return new int[] {disposal, transparent, delayMs};
    }

    private ImageBlock readImageBlock() {
        ImageBlock block = new ImageBlock();
        block.left = readU16LE();
        block.top = readU16LE();
        block.width = readU16LE();
        block.height = readU16LE();

        if (block.width <= 0 || block.height <= 0 || block.left < 0 || block.top < 0
            || block.left + block.width > width || block.top + block.height > height) {
            throw new StbFailureException("Bad GIF image descriptor");
        }

        int packed = readU8();
        boolean hasLocalColorTable = (packed & 0x80) != 0;
        block.interlaced = (packed & 0x40) != 0;
        int localColorTableSize = 1 << ((packed & 0x7) + 1);

        if (hasLocalColorTable) {
            block.localColorTable = new byte[localColorTableSize * 3];
            ensureAvailable(block.localColorTable.length, "GIF local color table truncated");
            for (int i = 0; i < block.localColorTable.length; i++) {
                block.localColorTable[i] = (byte) readU8();
            }
        }

        block.lzwMinCodeSize = readU8();
        block.lzwData = readSubBlocks();
        return block;
    }

    private byte[] readSubBlocks() {
        int total = 0;
        List<byte[]> chunks = new ArrayList<>();
        while (true) {
            int blockSize = readU8();
            if (blockSize == 0) break;
            byte[] chunk = new byte[blockSize];
            ensureAvailable(blockSize, "GIF sub-block truncated");
            ByteBuffer source = buffer.duplicate();
            source.position(pos);
            source.get(chunk, 0, blockSize);
            pos += blockSize;
            chunks.add(chunk);
            total += blockSize;
        }

        byte[] out = new byte[total];
        int off = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, off, chunk.length);
            off += chunk.length;
        }
        return out;
    }

    private void skipSubBlocks() {
        while (true) {
            int blockSize = readU8();
            if (blockSize == 0) {
                break;
            }
            ensureAvailable(blockSize, "GIF sub-block truncated");
            pos += blockSize;
        }
    }

    private void drawLzwImage(ImageBlock image, byte[] colorTable, int transparentIndex, byte[] canvas, byte[] history) {
        int pixelCount = image.width * image.height;
        int[] indices = decodeLzwIndices(image.lzwData, image.lzwMinCodeSize, pixelCount);

        for (int i = 0; i < indices.length; i++) {
            int idx = indices[i];
            if (idx < 0) continue;

            int localX = i % image.width;
            int localY = i / image.width;
            int mappedY = mapInterlacedY(localY, image.height, image.interlaced);
            int dstX = image.left + localX;
            int dstY = image.top + mappedY;
            if (dstX < 0 || dstX >= width || dstY < 0 || dstY >= height) continue;

            int cp = idx * 3;
            if (cp + 2 >= colorTable.length) continue;

            int pi = dstY * width + dstX;
            int p = pi * 4;
            history[pi] = 1;
            if (idx == transparentIndex) continue;
            canvas[p] = colorTable[cp];
            canvas[p + 1] = colorTable[cp + 1];
            canvas[p + 2] = colorTable[cp + 2];
            canvas[p + 3] = (byte) 0xFF;
        }
    }

    private int[] decodeLzwIndices(byte[] data, int minCodeSize, int expectedCount) {
        int clear = 1 << minCodeSize;
        int eoi = clear + 1;
        int codeSize = minCodeSize + 1;
        int avail = clear + 2;
        int oldCode = -1;
        int codeMask = (1 << codeSize) - 1;

        int[] prefix = new int[4096];
        int[] suffix = new int[4096];
        int[] stack = new int[4096];
        for (int i = 0; i < clear; i++) {
            prefix[i] = 0;
            suffix[i] = i;
        }

        int[] out = new int[expectedCount];
        Arrays.fill(out, -1);
        int outPos = 0;

        int datum = 0;
        int bits = 0;
        int dataPos = 0;
        int first = 0;
        int top = 0;

        while (outPos < expectedCount) {
            while (bits < codeSize) {
                if (dataPos >= data.length) {
                    return out;
                }
                datum |= (data[dataPos++] & 0xFF) << bits;
                bits += 8;
            }

            int code = datum & codeMask;
            datum >>>= codeSize;
            bits -= codeSize;

            if (code == clear) {
                codeSize = minCodeSize + 1;
                codeMask = (1 << codeSize) - 1;
                avail = clear + 2;
                oldCode = -1;
                continue;
            }
            if (code == eoi) {
                break;
            }

            int inCode = code;
            if (oldCode == -1) {
                if (code >= 4096) break;
                out[outPos++] = suffix[code];
                oldCode = code;
                first = suffix[code];
                continue;
            }

            if (code >= avail) {
                stack[top++] = first;
                code = oldCode;
            }

            while (code >= clear) {
                if (code >= 4096) return out;
                stack[top++] = suffix[code];
                code = prefix[code];
            }

            first = suffix[code];
            stack[top++] = first;

            while (top > 0 && outPos < expectedCount) {
                out[outPos++] = stack[--top];
            }

            if (avail < 4096) {
                prefix[avail] = oldCode;
                suffix[avail] = first;
                avail++;
                if ((avail & codeMask) == 0 && avail < 4096) {
                    codeSize++;
                    codeMask = (1 << codeSize) - 1;
                }
            }

            oldCode = inCode;
        }

        return out;
    }

    private StbImageResult toResult(GifFrame frame, int frameIndex, int desiredChannels) {
        int outChannels = desiredChannels == 0 ? 4 : desiredChannels;
        StbImageResult cached = (outChannels == 4) ? frame.rgba : frame.variants.get(outChannels);

        if (cached == null) {
            ByteBuffer converted = StbUtils.convertChannels(getAllocator(), frame.rgba.getData(), 4, width, height, outChannels, false);
            cached = new StbImageResult(converted, width, height, outChannels, outChannels, false, false);
            frame.variants.put(outChannels, cached);
        }

        return duplicateResultWithDesired(cached, desiredChannels, frameIndex);
    }

    private StbImageResult duplicateResultWithDesired(StbImageResult source, int desiredChannels, int frameIndex) {
        ByteBuffer sourceData = source.getData();
        ByteBuffer data = sourceData.duplicate();
        data.position(0);
        data.limit(sourceData.limit());
        return new StbImageResult(
            data,
            source.getWidth(),
            source.getHeight(),
            source.getChannels(),
            desiredChannels,
            source.is16Bit(),
            source.isHdr(),
            frameIndex
        );
    }

    private int mapInterlacedY(int y, int h, boolean interlaced) {
        if (!interlaced) {
            return y;
        }
        int[] starts = {0, 4, 2, 1};
        int[] steps = {8, 8, 4, 2};
        int row = 0;
        for (int pass = 0; pass < 4; pass++) {
            for (int yy = starts[pass]; yy < h; yy += steps[pass]) {
                if (row == y) return yy;
                row++;
            }
        }
        return y;
    }

    private int scanFrameCount() {
        ByteBuffer b = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] p = {0};

        String signature = readStringAt(b, p, 6, "GIF header truncated");
        if (!GIF87_SIGNATURE.equals(signature) && !GIF89_SIGNATURE.equals(signature)) {
            throw new StbFailureException("Not a GIF file");
        }

        int w = readU16LEAt(b, p, "GIF data truncated");
        int h = readU16LEAt(b, p, "GIF data truncated");
        StbLimits.validateDimensions(w, h);

        int packed = readU8At(b, p, "GIF data truncated");
        boolean hasGct = (packed & 0x80) != 0;
        int gctSize = 1 << ((packed & 0x7) + 1);
        readU8At(b, p, "GIF data truncated");
        readU8At(b, p, "GIF data truncated");

        if (hasGct) {
            ensureAvailableAt(b, p, gctSize * 3, "GIF global color table truncated");
            p[0] += gctSize * 3;
        }

        int count = 0;
        while (p[0] < b.limit()) {
            int tag = readU8At(b, p, "GIF data truncated");
            if (tag == TRAILER) {
                break;
            }
            if (tag == EXT_INTRODUCER) {
                readU8At(b, p, "GIF extension truncated");
                skipSubBlocksAt(b, p);
                continue;
            }
            if (tag != EXT_IMAGE) {
                throw new StbFailureException("Corrupt GIF stream");
            }

            count++;
            int left = readU16LEAt(b, p, "GIF image descriptor truncated");
            int top = readU16LEAt(b, p, "GIF image descriptor truncated");
            int iw = readU16LEAt(b, p, "GIF image descriptor truncated");
            int ih = readU16LEAt(b, p, "GIF image descriptor truncated");
            if (iw <= 0 || ih <= 0 || left < 0 || top < 0 || left + iw > w || top + ih > h) {
                throw new StbFailureException("Bad GIF image descriptor");
            }

            int imagePacked = readU8At(b, p, "GIF image descriptor truncated");
            boolean hasLct = (imagePacked & 0x80) != 0;
            int lctSize = 1 << ((imagePacked & 0x7) + 1);
            if (hasLct) {
                ensureAvailableAt(b, p, lctSize * 3, "GIF local color table truncated");
                p[0] += lctSize * 3;
            }

            readU8At(b, p, "GIF LZW minimum code size missing");
            skipSubBlocksAt(b, p);
        }

        return count;
    }

    private static void skipSubBlocksAt(ByteBuffer b, int[] p) {
        while (true) {
            int blockSize = readU8At(b, p, "GIF sub-block truncated");
            if (blockSize == 0) {
                return;
            }
            ensureAvailableAt(b, p, blockSize, "GIF sub-block truncated");
            p[0] += blockSize;
        }
    }

    private String readString(int length) {
        ensureAvailable(length, "GIF header truncated");
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (buffer.get(pos++) & 0xFF));
        }
        return sb.toString();
    }

    private int readU8() {
        ensureAvailable(1, "GIF data truncated");
        return buffer.get(pos++) & 0xFF;
    }

    private int readU16LE() {
        ensureAvailable(2, "GIF data truncated");
        int b0 = buffer.get(pos++) & 0xFF;
        int b1 = buffer.get(pos++) & 0xFF;
        return b0 | (b1 << 8);
    }

    private static int readU8At(ByteBuffer b, int[] p, String message) {
        ensureAvailableAt(b, p, 1, message);
        return b.get(p[0]++) & 0xFF;
    }

    private static int readU16LEAt(ByteBuffer b, int[] p, String message) {
        int b0 = readU8At(b, p, message);
        int b1 = readU8At(b, p, message);
        return b0 | (b1 << 8);
    }

    private static String readStringAt(ByteBuffer b, int[] p, int length, String message) {
        ensureAvailableAt(b, p, length, message);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (b.get(p[0]++) & 0xFF));
        }
        return sb.toString();
    }

    private static void ensureAvailableAt(ByteBuffer b, int[] p, int n, String message) {
        if (n < 0 || p[0] + n > b.limit()) {
            throw new StbFailureException(message);
        }
    }

    private void ensureAvailable(int n, String message) {
        if (n < 0 || pos + n > buffer.limit()) {
            throw new StbFailureException(message);
        }
    }
}
