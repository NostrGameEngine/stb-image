package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

public interface StbDecoder {
    public StbImageInfo info();
    public StbImageResult load(int desiredChannels);

    public IntFunction<ByteBuffer> getAllocator();


    public default StbImageResult load16(int desiredChannels) {
        // Load and convert to 16-bit if needed
        StbImageInfo imageInfo = info();
        if (imageInfo == null) {
            throw new StbFailureException("Unknown or invalid image format");
        }

        StbImageResult result = load(desiredChannels);
        // If already 16-bit, return as-is, otherwise convert
        if (result.is16Bit()) {
            return result;
        }

        // Convert 8-bit to 16-bit
        ByteBuffer data8 = result.getData();
        int channels = result.getChannels();
        ByteBuffer data16 = getAllocator().apply(result.getWidth() * result.getHeight() * channels * 2);

        for (int i = 0; i < data8.remaining(); i++) {
            int val = Byte.toUnsignedInt(data8.get(i));
            data16.putShort(i * 2, (short) (val | (val << 8)));
        }

        data16.flip();
        return new StbImageResult(data16, result.getWidth(), result.getHeight(), channels, desiredChannels, true, false);
    }

    public default StbImageResult loadf(ByteBuffer buffer, int desiredChannels) {
        // HDR files
        if (!(this instanceof HdrDecoder)) {
            throw new StbFailureException("Not an HDR image");
        }

        return  load(desiredChannels);
    }

}
