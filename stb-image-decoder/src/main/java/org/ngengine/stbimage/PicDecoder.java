package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Softimage PIC decoder.
 */
public class PicDecoder implements StbDecoder {

    private static final int MAX_PACKETS = 10;
    private static final int CHANNEL_R = 0x80;
    private static final int CHANNEL_G = 0x40;
    private static final int CHANNEL_B = 0x20;
    private static final int CHANNEL_A = 0x10;

    private final ByteBuffer buffer;
    private final IntFunction<ByteBuffer> allocator;
    private final boolean flipVertically;
    private int pos;

    private int width;
    private int height;
    private int srcChannels;

    private static final class PicPacket {
        int size;
        int type;
        int channel;
    }

    /**
     * Creates a PIC decoder instance.
     *
     * @param buffer source data
     * @param allocator output allocator
     * @param flipVertically true to vertically flip decoded output
     */
    public PicDecoder(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically) {
        this.buffer = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
        this.allocator = allocator;
        this.flipVertically = flipVertically;
        this.pos = 0;
    }

    /**
     * Tests whether the source starts with Softimage PIC magic values.
     *
     * @param buffer source bytes
     * @return true when signature and tag match PIC
     */
    public static boolean isPic(ByteBuffer buffer) {
        if (buffer.remaining() < 96) {
            return false;
        }
        ByteBuffer b = buffer.duplicate();
        int p = b.position();
        if ((b.get(p) & 0xFF) != 0x53 || (b.get(p + 1) & 0xFF) != 0x80
                || (b.get(p + 2) & 0xFF) != 0xF6 || (b.get(p + 3) & 0xFF) != 0x34) {
            return false;
        }
        return (b.get(p + 88) & 0xFF) == 'P' && (b.get(p + 89) & 0xFF) == 'I'
                && (b.get(p + 90) & 0xFF) == 'C' && (b.get(p + 91) & 0xFF) == 'T';
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageInfo info() {
        parseHeaderAndPackets(false);
        return new StbImageInfo(width, height, srcChannels, false, StbImageInfo.ImageFormat.PIC);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StbImageResult load(int desiredChannels) {
        PicPacket[] packets = parseHeaderAndPackets(true);

        ByteBuffer rgba = allocator.apply(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            rgba.put(i * 4 + 3, (byte) 0xFF);
        }

        decodePixels(packets, rgba);

        if (desiredChannels == 0) {
            desiredChannels = srcChannels;
        }

        ByteBuffer out = StbImage.convertChannels(allocator, rgba, 4, width, height, desiredChannels, false);
        if (flipVertically) {
            out = StbImage.verticalFlip(allocator, out, width, height, desiredChannels, false);
        }
        return new StbImageResult(out, width, height, desiredChannels, desiredChannels, false, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IntFunction<ByteBuffer> getAllocator() {
        return allocator;
    }

    private PicPacket[] parseHeaderAndPackets(boolean keepPacketPosition) {
        pos = 0;
        if (!readMagicAndTag()) {
            throw new StbFailureException("Not a PIC image");
        }

        pos = 92;
        width = readU16BE();
        height = readU16BE();
        StbImage.validateDimensions(width, height);

        ensureAvailable(8, "PIC header too short");
        skip(8); // ratio, fields, pad

        int actComp = 0;
        PicPacket[] packets = new PicPacket[MAX_PACKETS];
        int packetCount = 0;
        int chained;
        do {
            if (packetCount == MAX_PACKETS) {
                throw new StbFailureException("Bad PIC format: too many packets");
            }
            ensureAvailable(4, "PIC packet header too short");
            chained = readU8();
            PicPacket packet = new PicPacket();
            packet.size = readU8();
            packet.type = readU8();
            packet.channel = readU8();
            if (packet.size != 8) {
                throw new StbFailureException("Bad PIC format: packet isn't 8bpp");
            }
            actComp |= packet.channel;
            packets[packetCount++] = packet;
        } while (chained != 0);

        srcChannels = (actComp & CHANNEL_A) != 0 ? 4 : 3;
        PicPacket[] result = Arrays.copyOf(packets, packetCount);
        if (!keepPacketPosition) {
            pos = 0;
        }
        return result;
    }

    private void decodePixels(PicPacket[] packets, ByteBuffer dest) {
        byte[] value = new byte[4];
        for (int y = 0; y < height; y++) {
            int rowBase = y * width * 4;
            for (PicPacket packet : packets) {
                int x = 0;
                switch (packet.type) {
                    case 0:
                        while (x < width) {
                            int pixelBase = rowBase + x * 4;
                            readVal(packet.channel, dest, pixelBase);
                            x++;
                        }
                        break;
                    case 1:
                        while (x < width) {
                            int count = readU8();
                            if (count > width - x) {
                                count = width - x;
                            }
                            readVal(packet.channel, value, 0);
                            for (int i = 0; i < count; i++) {
                                int pixelBase = rowBase + (x + i) * 4;
                                copyVal(packet.channel, dest, pixelBase, value, 0);
                            }
                            x += count;
                        }
                        break;
                    case 2:
                        while (x < width) {
                            int count = readU8();
                            if (count >= 128) {
                                if (count == 128) {
                                    count = readU16BE();
                                } else {
                                    count -= 127;
                                }
                                if (count > width - x) {
                                    throw new StbFailureException("Bad PIC file: scanline overrun");
                                }
                                readVal(packet.channel, value, 0);
                                for (int i = 0; i < count; i++) {
                                    int pixelBase = rowBase + (x + i) * 4;
                                    copyVal(packet.channel, dest, pixelBase, value, 0);
                                }
                            } else {
                                count += 1;
                                if (count > width - x) {
                                    throw new StbFailureException("Bad PIC file: scanline overrun");
                                }
                                for (int i = 0; i < count; i++) {
                                    int pixelBase = rowBase + (x + i) * 4;
                                    readVal(packet.channel, dest, pixelBase);
                                }
                            }
                            x += count;
                        }
                        break;
                    default:
                        throw new StbFailureException("Bad PIC format: unsupported compression type");
                }
            }
        }
    }

    private boolean readMagicAndTag() {
        ensureAvailable(92, "PIC header too short");
        if (readU8() != 0x53 || readU8() != 0x80 || readU8() != 0xF6 || readU8() != 0x34) {
            return false;
        }
        skip(84);
        return readU8() == 'P' && readU8() == 'I' && readU8() == 'C' && readU8() == 'T';
    }

    private void readVal(int channel, ByteBuffer dst, int base) {
        if ((channel & CHANNEL_R) != 0) {
            dst.put(base, (byte) readU8());
        }
        if ((channel & CHANNEL_G) != 0) {
            dst.put(base + 1, (byte) readU8());
        }
        if ((channel & CHANNEL_B) != 0) {
            dst.put(base + 2, (byte) readU8());
        }
        if ((channel & CHANNEL_A) != 0) {
            dst.put(base + 3, (byte) readU8());
        }
    }

    private void readVal(int channel, byte[] dst, int base) {
        if ((channel & CHANNEL_R) != 0) {
            dst[base] = (byte) readU8();
        }
        if ((channel & CHANNEL_G) != 0) {
            dst[base + 1] = (byte) readU8();
        }
        if ((channel & CHANNEL_B) != 0) {
            dst[base + 2] = (byte) readU8();
        }
        if ((channel & CHANNEL_A) != 0) {
            dst[base + 3] = (byte) readU8();
        }
    }

    private void copyVal(int channel, ByteBuffer dst, int base, byte[] src, int srcBase) {
        if ((channel & CHANNEL_R) != 0) {
            dst.put(base, src[srcBase]);
        }
        if ((channel & CHANNEL_G) != 0) {
            dst.put(base + 1, src[srcBase + 1]);
        }
        if ((channel & CHANNEL_B) != 0) {
            dst.put(base + 2, src[srcBase + 2]);
        }
        if ((channel & CHANNEL_A) != 0) {
            dst.put(base + 3, src[srcBase + 3]);
        }
    }

    private int readU8() {
        ensureAvailable(1, "PIC file too short");
        return buffer.get(pos++) & 0xFF;
    }

    private int readU16BE() {
        int hi = readU8();
        int lo = readU8();
        return (hi << 8) | lo;
    }

    private void skip(int n) {
        ensureAvailable(n, "PIC file too short");
        pos += n;
    }

    private void ensureAvailable(int n, String message) {
        if (pos + n > buffer.limit()) {
            throw new StbFailureException(message);
        }
    }
}
