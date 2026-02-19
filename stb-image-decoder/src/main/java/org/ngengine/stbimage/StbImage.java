package org.ngengine.stbimage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;


/**
 * Pure Java implementation of stb_image.h
 */
public class StbImage {



    private final IntFunction<ByteBuffer> allocator;
    private boolean convertIphonePngToRgb = false;
    private boolean unpremultiplyOnLoad = false;
    private boolean fillGifFirstFrameBackground = false;

    @FunctionalInterface
    public static interface StbDecoderInstancer {
        public StbDecoder create(ByteBuffer buffer, IntFunction<ByteBuffer> allocator, boolean flipVertically);
    }

    private static class DecoderRegistration {
        final StbDecoderInstancer instancer;
        final Predicate<ByteBuffer> formatChecker;
        final String format;
        DecoderRegistration(String format, StbDecoderInstancer instancer, Predicate<ByteBuffer> formatChecker) {
            this.instancer = instancer;
            this.formatChecker = formatChecker;
            this.format = format.toUpperCase();
        }

        boolean isFormat(String fmt) {
            return this.format.equals(fmt.toUpperCase());
        }
    }

    private List<DecoderRegistration> decoders = new ArrayList<>();

    /**
     * Registers a decoder type and its format probe.
     *
     * @param format format name of the decoder
     * @param instancer decoder class with constructor (ByteBuffer, IntFunction, boolean)
     * @param formatChecker predicate that returns true when buffer matches decoder format
     */
    public void registerDecoder(String format, StbDecoderInstancer instancer, Predicate<ByteBuffer> formatChecker) {
        if(decoders.stream().anyMatch(reg -> reg.instancer.equals(instancer) || reg.isFormat(format))) {
            throw new IllegalArgumentException("Decoder already registered: " + format);
        }
        decoders.add(new DecoderRegistration(format, instancer, formatChecker));

        // Ensure tga decoder is the last
        decoders.sort((a, b) -> {
            if (a.isFormat("TGA")) return 1;
            if (b.isFormat("TGA")) return -1;
            return 0;
        });
    }

    /**
     * Unregisters a decoder previously added with {@link #registerDecoder(String, StbDecoderInstancer, Predicate)}.
     *
     * @param format format name of the decoder to remove
     */
    public void unregisterDecoder(String format) {
        decoders.removeIf(reg -> reg.isFormat(format));
    }

    /**
     * Unregisters a decoder by its instancer reference.
     * @param instancer decoder class reference to remove
     */
    public void unregisterDecoder(StbDecoderInstancer instancer) {
        decoders.removeIf(reg -> reg.instancer.equals(instancer));
    }

    /**
     * Unregisters all decoders, leaving the instance with no supported formats.
     */
    public void unregisterAllDecoders() {
        decoders.clear();
    }

     /**
      * Returns a list of registered decoder formats.
      *
      * @return list of format names
      */
     public List<String> getRegisteredFormats() {
         List<String> formats = new ArrayList<>();
         for (DecoderRegistration reg : decoders) {
             formats.add(reg.format);
         }
         return formats;
     }

     /**
      * Returns the number of registered decoders.
      *
      * @return decoder count
      */
     public int getDecoderCount() {
         return decoders.size();
     }

    /**
     * Creates an instance using heap allocation ({@link ByteBuffer#allocate(int)}).
     */
    public StbImage() {
        this(null);
    }

    /**
     * Creates an instance with a custom output buffer allocator.
     *
     * @param allocator allocation strategy, or null to use {@link ByteBuffer#allocate(int)}
     */
    public StbImage(IntFunction<ByteBuffer> allocator){
        this.allocator = allocator==null?ByteBuffer::allocate:allocator;
        registerDecoder("PNG", PngDecoder::new, PngDecoder::isPng);
        registerDecoder("JPEG", JpegDecoder::new, JpegDecoder::isJpeg);
        registerDecoder("BMP", BmpDecoder::new, BmpDecoder::isBmp);
        registerDecoder("GIF", GifDecoder::new, GifDecoder::isGif);
        registerDecoder("PNM", PnmDecoder::new, PnmDecoder::isPnm);
        registerDecoder("PSD", PsdDecoder::new, PsdDecoder::isPsd);
        registerDecoder("HDR", HdrDecoder::new, HdrDecoder::isHdr);
        registerDecoder("PIC", PicDecoder::new, PicDecoder::isPic);
        registerDecoder("TGA", TgaDecoder::new, TgaDecoder::isTga);
    }

    /**
     * Enables/disables automatic iPhone PNG BGR->RGB conversion for CgBI PNGs.
     *
     * @param convertIphonePngToRgb true to convert CgBI channel order to RGB(A)
     */
    public void setConvertIphonePngToRgb(boolean convertIphonePngToRgb) {
        this.convertIphonePngToRgb = convertIphonePngToRgb;
    }

    /**
     * Returns whether iPhone PNG channel conversion is enabled.
     *
     * @return true when CgBI PNGs are converted from BGR(A) to RGB(A)
     */
    public boolean isConvertIphonePngToRgb() {
        return convertIphonePngToRgb;
    }

    /**
     * Enables/disables unpremultiply for iPhone PNG alpha data.
     *
     * @param unpremultiplyOnLoad true to unpremultiply by alpha during decode
     */
    public void setUnpremultiplyOnLoad(boolean unpremultiplyOnLoad) {
        this.unpremultiplyOnLoad = unpremultiplyOnLoad;
    }

    /**
     * Returns whether iPhone PNG unpremultiply is enabled.
     *
     * @return true when unpremultiply is enabled
     */
    public boolean isUnpremultiplyOnLoad() {
        return unpremultiplyOnLoad;
    }

    /**
     * Enables/disables logical-screen background fill for untouched pixels on GIF first frame.
     *
     * <p>True matches stb_image behavior. False keeps untouched pixels transparent.</p>
     *
     * @param fillGifFirstFrameBackground true to match stb-style first-frame fill
     */
    public void setFillGifFirstFrameBackground(boolean fillGifFirstFrameBackground) {
        this.fillGifFirstFrameBackground = fillGifFirstFrameBackground;
    }

    /**
     * Returns whether GIF first-frame untouched pixels are filled from background color.
     *
     * @return true when stb-style first-frame background fill is enabled
     */
    public boolean isFillGifFirstFrameBackground() {
        return fillGifFirstFrameBackground;
    }

    /**
     * Selects and instantiates a decoder for the provided buffer.
     *
     * @param buffer input image bytes
     * @param flipVertically true to vertically flip decoded output
     * @return matching decoder instance
     */
    public StbDecoder getDecoder(ByteBuffer buffer, boolean flipVertically) {
        if (buffer.remaining() < 12) {
            throw new StbFailureException("Buffer too small to determine format");
        }
        for (DecoderRegistration reg : decoders) {
            if (reg.formatChecker.test(buffer)) {
                try {
                    IntFunction<ByteBuffer> allocatorWrapper = new IntFunction<ByteBuffer>() {
                        private long totalAllocated = 0;
                        @Override
                        public ByteBuffer apply(int size) {
                            if (size < 0) {
                                throw new StbFailureException("Negative allocation size requested");
                            }
                            StbLimits.checkMaxSingleAllocationBytes(size);
                            
                            long nextTotal = totalAllocated + (long) size;
                            StbLimits.checkMaxTotalAllocationPerDecodeBytes(nextTotal);
                            totalAllocated = nextTotal;
                            
                            return allocator.apply(size);
                        }
                    };
                    StbDecoder decoder = reg.instancer.create(buffer, allocatorWrapper, flipVertically);
                    if (decoder instanceof PngDecoder) {
                        PngDecoder pngDecoder = (PngDecoder) decoder;
                        pngDecoder.setConvertIphonePngToRgb(convertIphonePngToRgb);
                        pngDecoder.setUnpremultiplyOnLoad(unpremultiplyOnLoad);
                    } else if (decoder instanceof GifDecoder) {
                        GifDecoder gifDecoder = (GifDecoder) decoder;
                        gifDecoder.setFillFirstFrameBackground(fillGifFirstFrameBackground);
                    }
                    return decoder;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate decoder: " + reg.format, e);
                }
            }
        }
        throw new StbFailureException("Unknown or unsupported image format");
    }
    
}
