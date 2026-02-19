package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/**
 * Common decoder contract used by all image format implementations.
 */
public interface StbDecoder {
    
    /**
     * Reads image metadata without fully decoding pixel data.
     *
     * @return image information
     */
    public StbImageInfo info();

    /**
     * Decodes image pixels.
     *
     * @param desiredChannels requested output channels (0 keeps source/default behavior)
     * @return decoded image result
     */
    public StbImageResult load(int desiredChannels);

    /**
     * Returns the allocator used by this decoder for output buffers.
     *
     * @return allocator function
     */
    public IntFunction<ByteBuffer> getAllocator();


    /**
     * Loads image data as 16-bit channels.
     *
     * <p>If the underlying format natively decodes as 8-bit, this method upconverts by
     * replicating 8-bit values into 16-bit ({@code v -> (v << 8) | v}).</p>
     *
     * @param desiredChannels requested output channels (0 keeps source/default behavior)
     * @return decoded 16-bit result
     */
    public default StbImageResult load16(int desiredChannels) {
        // Load and convert to 16-bit if needed
        StbImageInfo imageInfo = info();
        if (imageInfo == null) {
            throw new StbFailureException("Unknown or invalid image format");
        }

        StbImageResult result = load(desiredChannels);
        
        // prevent accidental misuse on HDR decoders since load16 doesn't make sense for them
        if (result.isHdr()) {
            throw new StbFailureException("Cannot load16 from HDR data; use loadf instead");
        }

        // If already 16-bit, return as-is, otherwise convert
        if (result.is16Bit()) {
            return result;
        }

        // Convert 8-bit to 16-bit
        ByteBuffer data8 = result.getData();
        int channels = result.getChannels();
        int size16 = StbLimits.checkedImageBufferSize(result.getWidth(), result.getHeight(), channels, 2);
        ByteBuffer data16 = getAllocator().apply(size16);

        for (int i = 0; i < data8.remaining(); i++) {
            int val = Byte.toUnsignedInt(data8.get(i));
            data16.putShort(i * 2, (short) (val | (val << 8)));
        }

        data16.flip();
        return new StbImageResult(data16, result.getWidth(), result.getHeight(), channels, desiredChannels, true, false);
    }

    /**
     * Loads image data as floating-point output for HDR-capable decoders.
     *
     * @param desiredChannels requested output channels (0 keeps source/default behavior)
     * @return decoded floating-point result
     */
    public default StbImageResult loadf(int desiredChannels) {
        if (!(this instanceof HdrDecoder)) {
            throw new StbFailureException("cannot loadf from non-HDR images, use load or load16 instead");
        }
        StbImageResult result = load(desiredChannels);
        if (!result.isHdr()) {
            throw new StbFailureException("Decoder did not return HDR float data");
        }
        return result;
    }
}
