package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated JVM fixture: configure public limits before the first decoder locks them. */
public final class PngLimitsProbe {
    public static void main(String[] args) {
        boolean single = "single".equals(args[0]);
        StbLimits.setMaxSingleAllocationBytes(single ? 16 : 64);
        StbLimits.setMaxTotalAllocationPerDecodeBytes(single ? 64 : 16);
        // Only 86 source bytes. Each IDAT fits the 16-byte limit; their sum does not.
        ByteBuffer input = ByteBuffer.allocate(86);
        input.put(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        input.putInt(13).putInt(0x49484452).putInt(1).putInt(1).put((byte) 8).put(new byte[4]).putInt(0);
        input.putInt(8).putInt(0x49444154).put(new byte[8]).putInt(0);
        input.putInt(9).putInt(0x49444154).put(new byte[9]).putInt(0);
        input.putInt(0).putInt(0x49454e44).putInt(0);
        input.flip();
        AtomicInteger allocations = new AtomicInteger();
        PngDecoder decoder = new PngDecoder(input, size -> {
            allocations.incrementAndGet();
            return ByteBuffer.allocate(size);
        }, false);
        try {
            decoder.load(1);
            throw new AssertionError("Expected aggregate IDAT limit rejection");
        } catch (StbFailureException expected) {
            String marker = single ? "per-buffer limit" : "per-decode limit";
            if (!expected.getMessage().contains(marker) || allocations.get() != 0) {
                throw new AssertionError("Incorrect rejection: " + expected.getMessage(), expected);
            }
        }
        System.out.println("PASS " + args[0]);
    }
}
