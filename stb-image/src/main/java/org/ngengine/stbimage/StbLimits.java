package org.ngengine.stbimage;

/**
 * Limits resource usage for every instance of every decoder.
 * 
 * Usually the defaults are fine, but if you wish to change the limits, be sure to set them before any decoder is initialized 
 * as they will be locked afterwards and subsequent calls to the set methods will do nothing.
 * The limits are applied to all decoders and are not specific to any one format, so they can be used as a global safety net against OOM errors when decoding untrusted content.
 */
public class StbLimits {
    private static int maxSingleAllocationBytes = 256 * 1024 * 1024; // 256MB default per-allocation limit
    private static long maxTotalAllocationPerDecodeBytes = 1L * 1024 * 1024 * 1024; // 1GB default per-decode cumulative limit
    private static int maxDimensions = 1 << 24;
    private static volatile boolean locked = false;
    private static int stalledRoundsLimit = 8;

    public static void lock(){
        locked = true;
    }




    /**
     * Set how many rounds a loop can stall (make no progress) before we consider it broken
     * 
     * @param stalledRoundsLimit number of rounds without progress before considering stalled (must be positive)
     */
    public static void setStalledRoundsLimit(int stalledRoundsLimit) {
        if (locked) return;
        if (stalledRoundsLimit <= 0) {
            throw new IllegalArgumentException("Stalled rounds limit must be positive");
        }
        StbLimits.stalledRoundsLimit = stalledRoundsLimit;
    }



  

    /**
     * Sets the maximum allowed image dimensions for decoding.
     * @param maxDimensions maximum width or height in pixels (must be positive)
     */
    public static void setMaxDimensions(int maxDimensions) {
        if (locked) return;
        if (maxDimensions <= 0) {
            throw new IllegalArgumentException("Max dimensions must be positive");
        }
        StbLimits.maxDimensions = maxDimensions;
    }


    /**
     * Sets the maximum cumulative bytes allocated by one decoder instance.
     *
     * @param maxTotalAllocationPerDecodeBytes cumulative byte limit (0 for unlimited ie. handled by the allocator itself)
     */
    public static void setMaxTotalAllocationPerDecodeBytes(long maxTotalAllocationPerDecodeBytes) {
        if (locked) return;
        StbLimits.maxTotalAllocationPerDecodeBytes = maxTotalAllocationPerDecodeBytes;
    }

    /**
     * Sets the maximum number of bytes allowed for any single buffer allocation during decode.
     *
     * @param maxSingleAllocationBytes byte limit (0 for unlimited ie. handled by the allocator itself)
     */
    public static void setMaxSingleAllocationBytes(int maxSingleAllocationBytes) {
        if (locked) return;
        StbLimits.maxSingleAllocationBytes = maxSingleAllocationBytes;
    }

    /**
     * Validates image dimensions against stb-style safety limits.
     *
     * @param width image width
     * @param height image height
     */
    public static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new StbFailureException("Invalid image dimensions: " + width + "x" + height);
        }
        if (StbLimits.maxDimensions > 0 && (width > StbLimits.maxDimensions || height > StbLimits.maxDimensions)) {
            throw new StbFailureException("Image dimensions exceed maximum");
        }
        long totalPixels = (long) width * height;
        if (totalPixels > 0x7FFFFFFF) {
            throw new StbFailureException("Image too large");
        }
    }

    /**
     * Computes pixel count with overflow checks.
     * @param width image width
     * @param height image height
     *
     * @return checked pixel count
     */
    public static int checkedPixelCount(int width, int height) {
        validateDimensions(width, height);
        long v = (long) width * height;
        if (v <= 0 || v > Integer.MAX_VALUE) {
            throw new StbFailureException("Size overflow");
        }
        return (int) v;
    }

    /**
     * Computes byte size for interleaved image buffers with overflow checks.
     * @param width image width
     * @param height image height
     * @param channels channel count
     * @param bytesPerChannel bytes per channel
     *
     * @return checked total buffer size in bytes
     */
    public static int checkedImageBufferSize(int width, int height, int channels, int bytesPerChannel) {
        if (channels <= 0 || bytesPerChannel <= 0) {
            throw new StbFailureException("Invalid format");
        }
        int pixels = checkedPixelCount(width, height);
        long v = (long) pixels * channels * bytesPerChannel;
        if (v <= 0 || v > Integer.MAX_VALUE) {
            throw new StbFailureException("Buffer size overflow");
        }
        return (int) v;
    }

    /**
     * Checks if the requested single allocation bytes exceeds the configured limit.
     * @param requestedBytes
     */
    public static void checkMaxSingleAllocationBytes(long requestedBytes) {
        if (maxSingleAllocationBytes > 0 && (requestedBytes <= 0 ||requestedBytes > maxSingleAllocationBytes)) {
            if(requestedBytes <= 0) {
                throw new StbFailureException("Invalid allocation size requested: " + requestedBytes);
            }
            throw new StbFailureException("Allocation exceeds per-buffer limit: " + requestedBytes + " > " + maxSingleAllocationBytes);
        }
    }

    /**
     * Checks if the requested cumulative allocation bytes for a decode operation exceeds the configured limit.
     * @param requestedBytes
     */
    public static void checkMaxTotalAllocationPerDecodeBytes(long requestedBytes) {
        if (maxTotalAllocationPerDecodeBytes > 0 && (requestedBytes <= 0 || requestedBytes > maxTotalAllocationPerDecodeBytes)) {
            if(requestedBytes <= 0) {
                throw new StbFailureException("Invalid cumulative allocation size requested: " + requestedBytes);
            }
            throw new StbFailureException("Allocation exceeds per-decode limit: " + requestedBytes + " > " + maxTotalAllocationPerDecodeBytes);
        }
    }

    /**
     * Checks if stalled rounds have exceeded the configured limit.
     *
     * @param stalledRounds current count of stalled rounds
     * @return true if stalled rounds exceed the limit
     */
    public static void checkStalledRounds(int stalledRounds) {
        if (stalledRoundsLimit > 0 && (stalledRounds<0||stalledRounds > stalledRoundsLimit)) {
            if(stalledRounds < 0) {
                throw new StbFailureException("Invalid stalled rounds count: " + stalledRounds);
            }
            throw new StbFailureException("Operation stalled for " + stalledRounds + " rounds, exceeding the limit of " + stalledRoundsLimit);
        }
    }
    
}
