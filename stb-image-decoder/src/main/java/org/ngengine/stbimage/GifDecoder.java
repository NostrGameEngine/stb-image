package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * GIF decoder.
 *
 * Supports regular stbi_load-like first-frame decode through {@link #load(int)}
 * and frame-by-frame decode for animated GIFs through {@link #loadNextFrame(int)}.
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

    private boolean parsed;
    private final List<GifFrame> frames = new ArrayList<>();
    private int nextFrameIndex;
    private int lastFrameDelayMs;
    // keep untouched first-frame pixels transparent for viewer-friendly rendering.
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
        final byte[] rgba;
        final int delayMs;

        GifFrame(byte[] rgba, int delayMs) {
            this.rgba = rgba;
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

    /**
     * {@inheritDoc}
     */
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
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageInfo info() {
        try {
            pos = 0;
            String signature = readString(6);
            if (!signature.equals(GIF87_SIGNATURE) && !signature.equals(GIF89_SIGNATURE)) {
                return null;
            }

            width = readU16LE();
            height = readU16LE();
            int packed = readU8();
            hasGlobalColorTable = (packed & 0x80) != 0;
            globalColorTableSize = 1 << ((packed & 0x7) + 1);
            backgroundColorIndex = readU8();
            readU8(); // pixel aspect ratio

            return new StbImageInfo(width, height, 4, false, StbImageInfo.ImageFormat.GIF);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes and returns the first frame (stb-style load behavior).
     *
     * @param desiredChannels requested channel count
     * @return decoded first frame
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        ensureParsed();
        if (frames.isEmpty()) {
            throw new StbFailureException("No image data found");
        }
        GifFrame first = frames.get(0);
        lastFrameDelayMs = first.delayMs;
        nextFrameIndex = frames.size() > 1 ? 1 : 0;
        return toResult(first.rgba, desiredChannels);
    }

    /**
     * Loads the next GIF frame, looping back to frame 0 after the last frame.
     */
    public StbImageResult loadNextFrame(int desiredChannels) {
        ensureParsed();
        if (frames.isEmpty()) {
            throw new StbFailureException("No image data found");
        }
        if (nextFrameIndex >= frames.size()) {
            nextFrameIndex = 0;
        }
        GifFrame frame = frames.get(nextFrameIndex);
        nextFrameIndex = (nextFrameIndex + 1) % frames.size();
        lastFrameDelayMs = frame.delayMs;
        return toResult(frame.rgba, desiredChannels);
    }

    /**
     * Returns the number of decoded frames.
     *
     * @return frame count
     */
    public int getFrameCount() {
        ensureParsed();
        return frames.size();
    }

    /**
     * Indicates whether the GIF contains more than one frame.
     *
     * @return true for animated GIFs
     */
    public boolean isAnimated() {
        return getFrameCount() > 1;
    }

    /**
     * Returns delay in milliseconds for the last frame returned by {@link #load(int)}
     * or {@link #loadNextFrame(int)}.
     *
     * @return frame delay in milliseconds
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
     *
     * @param fillFirstFrameBackground true to apply background-color fill on frame 0
     */
    public void setFillFirstFrameBackground(boolean fillFirstFrameBackground) {
        this.fillFirstFrameBackground = fillFirstFrameBackground;
    }

    private void ensureParsed() {
        if (parsed) return;
        parseAllFrames();
        parsed = true;
    }

    private void parseAllFrames() {
        pos = 0;
        frames.clear();
        nextFrameIndex = 0;
        lastFrameDelayMs = 0;

        String signature = readString(6);
        if (!signature.equals(GIF87_SIGNATURE) && !signature.equals(GIF89_SIGNATURE)) {
            throw new StbFailureException("Not a GIF file");
        }

        width = readU16LE();
        height = readU16LE();

        int packed = readU8();
        hasGlobalColorTable = (packed & 0x80) != 0;
        globalColorTableSize = 1 << ((packed & 0x7) + 1);
        backgroundColorIndex = readU8();
        readU8(); // pixel aspect ratio

        StbImage.validateDimensions(width, height);

        if (hasGlobalColorTable) {
            globalColorTable = new byte[globalColorTableSize * 3];
            for (int i = 0; i < globalColorTable.length; i++) {
                globalColorTable[i] = (byte) readU8();
            }
        } else {
            globalColorTable = new byte[0];
        }

        int pixelCount = width * height;
        byte[] canvas = new byte[pixelCount * 4];
        byte[] background = new byte[pixelCount * 4];
        byte[] history = new byte[pixelCount];
        byte[] prevFrame = null;
        byte[] prevPrevFrame = null;

        int previousDisposal = 0;
        boolean firstFrame = true;

        int gceDisposal = 0;
        int gceTransparent = -1;
        int gceDelayMs = 0;

        while (pos < buffer.limit()) {
            int tag = readU8();

            if (tag == EXT_IMAGE) {
                ImageBlock image = readImageBlock();

                if (!firstFrame) {
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
                frames.add(new GifFrame(frameCopy, gceDelayMs));

                prevPrevFrame = prevFrame;
                prevFrame = frameCopy;
                previousDisposal = gceDisposal;
                firstFrame = false;
            } else if (tag == EXT_INTRODUCER) {
                int ext = readU8();
                if (ext == EXT_GRAPHICS_CONTROL) {
                    int[] gce = readGraphicsControlBlock();
                    gceDisposal = gce[0];
                    gceTransparent = gce[1];
                    gceDelayMs = gce[2];
                } else {
                    skipSubBlocks();
                }
            } else if (tag == TRAILER) {
                break;
            } else {
                throw new StbFailureException("Corrupt GIF stream");
            }
        }

        if (frames.isEmpty()) {
            throw new StbFailureException("No image data found");
        }
    }

    private int[] readGraphicsControlBlock() {
        int blockSize = readU8();
        if (blockSize != 4) {
            pos += Math.max(0, blockSize);
            // consume terminator if present
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
        readU8(); // terminator

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
            buffer.get(pos, chunk, 0, blockSize);
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

    private StbImageResult toResult(byte[] rgba, int desiredChannels) {
        int outChannels = desiredChannels == 0 ? 4 : desiredChannels;

        ByteBuffer src = allocator.apply(rgba.length);
        for (int i = 0; i < rgba.length; i++) {
            src.put(i, rgba[i]);
        }
        src.limit(rgba.length);

        ByteBuffer result = StbImage.convertChannels(getAllocator(), src, 4, width, height, outChannels, false);

        if (flipVertically) {
            result = StbImage.verticalFlip(getAllocator(), result, width, height, outChannels, false);
        }

        return new StbImageResult(result, width, height, outChannels, desiredChannels, false, false);
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

    private String readString(int length) {
        StringBuilder sb = new StringBuilder(length);
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
