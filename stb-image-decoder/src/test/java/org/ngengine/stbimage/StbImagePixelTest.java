package org.ngengine.stbimage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify decoded pixel data matches the official stb_image.h C implementation.
 * Tests each image in index.txt against the corresponding expected binary file.
 */
public class StbImagePixelTest {


    /** Maximum allowed difference per pixel channel  */
    public static final int PIXEL_TOLERANCE = 3;

    @TempDir
    static Path tempDir;

    /** List of image paths from index.txt */
    private static final List<String> imagePaths = new ArrayList<>();
    private static StbImage stbImage;

    @BeforeAll
    static void setUp() throws IOException {
        stbImage = new StbImage();
        // Reference binaries are generated with stb_image semantics.
        stbImage.setFillGifFirstFrameBackground(true);
        
        // Load index.txt to get list of images
        loadImageIndex();
    }

    private static void loadImageIndex() throws IOException {
        try (InputStream is = StbImagePixelTest.class.getClassLoader()
                .getResourceAsStream("testData/index.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    imagePaths.add(line);
                }
            }
        }
        System.out.println("Loaded " + imagePaths.size() + " images from index.txt");
    }

    private ByteBuffer loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            byte[] data = is.readAllBytes();
            return ByteBuffer.wrap(data);
        }
    }

    /**
     * Load reference data from C stb_image - format is:
     * int width, int height, int channels, then raw pixel data
     */
    private int[] loadReference(String refName) throws IOException {
        ByteBuffer ref = loadResource("testData/expected/" + refName);
        ref.rewind();
        // Use little-endian to match C binary format
        ref.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int width = ref.getInt();
        int height = ref.getInt();
        int channelsInFile = ref.getInt();
        int pixelCount = width * height;
        int bytesLeft = ref.remaining();
        int channels = (pixelCount > 0) ? (bytesLeft / pixelCount) : channelsInFile;
        if (channels <= 0 || channels > 4 || channels * pixelCount != bytesLeft) {
            channels = channelsInFile;
        }
        int[] result = new int[3 + width * height * channels];
        result[0] = width;
        result[1] = height;
        result[2] = channels;
        // Read pixels as unsigned
        for (int i = 0; i < width * height * channels; i++) {
            result[3 + i] = Byte.toUnsignedInt(ref.get());
        }
        return result;
    }

    private void assertPixelsMatch(ByteBuffer decoded, int[] expected, int width, int height, int channels, boolean isHdr) {
        decoded.rewind();

        byte[] decodedPixels;
        // Use tolerance for HDR - higher than standard due to float->byte conversion
        // and potential differences in reference generation
        int tolerance =  PIXEL_TOLERANCE;

        if (isHdr) {
            // For HDR, convert floats to bytes (0-255) for comparison
            // Uses gamma 2.2 like stb_image.h's hdr_to_ldr conversion
            decodedPixels = new byte[width * height * channels];
            double gammaInv = 1.0 / 2.2;
            for (int i = 0; i < width * height * channels; i++) {
                float f = decoded.getFloat(i * 4);
                // Convert float to byte: z = pow(data, 1/2.2) * 255 + 0.5f
                double z = Math.pow(f, gammaInv) * 255.0 + 0.5;
                int byteVal = (int) z;
                if (byteVal < 0) byteVal = 0;
                if (byteVal > 255) byteVal = 255;
                decodedPixels[i] = (byte) byteVal;
            }
        } else {
            decodedPixels = new byte[width * height * channels];
            decoded.get(decodedPixels);
        }

        int offset = 3; // skip width, height, channels
        int maxDiff = 0;
        int maxDiffIdx = -1;
        int failCount = 0;
        for (int i = 0; i < width * height * channels; i++) {
            int expectedVal = expected[offset + i];
            int decodedVal = Byte.toUnsignedInt(decodedPixels[i]);
            int diff = Math.abs(expectedVal - decodedVal);
            if (diff > maxDiff) {
                maxDiff = diff;
                maxDiffIdx = i;
            }
            if (diff > tolerance) {
                failCount++;
            }
        }
        System.out.println("##### Max diff: " + maxDiff + " at pixel " + maxDiffIdx + ", fails=" + failCount + " #####");
        for (int i = 0; i < width * height * channels; i++) {
            int expectedVal = expected[offset + i];
            int decodedVal = Byte.toUnsignedInt(decodedPixels[i]);
            int diff = Math.abs(expectedVal - decodedVal);
            if (diff > tolerance) {
                assertTrue(diff <= tolerance,
                    String.format("Pixel %d mismatch: decoded=%d, expected=%d, diff=%d, tolerance=%d",
                        i, decodedVal, expectedVal, diff, tolerance));
            }
        }
    }

    private boolean matchesExtensionFilter(String filename) {
        String extFilter = System.getenv("TEST_EXT");
        if (extFilter == null || extFilter.isBlank()) return true;
        extFilter = extFilter.toLowerCase();
        if (extFilter.startsWith(".")) extFilter = extFilter.substring(1);
        String fileExt = "";
        int idx = filename.lastIndexOf('.');
        if (idx >= 0 && idx < filename.length() - 1) fileExt = filename.substring(idx + 1).toLowerCase();
        return fileExt.equals(extFilter);
    }

    private int testLimit(String envName, int fallback) {
        String v = System.getenv(envName);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean envFlag(String envName, boolean fallback) {
        String v = System.getenv(envName);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        v = v.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    /**
     * Test loading and decoding each image in the index
     */
    @Test
    void testAllImagesLoad() throws IOException {
        int limit = testLimit("TEST_LOAD_LIMIT", 200);

        int loaded = 0;
        int failed = 0;
        List<String> failedImages = new ArrayList<>();

        int count = 0;
        for (String imagePath : imagePaths) {
            if (count++ >= limit) break;

            String filename = imagePath.substring(imagePath.lastIndexOf('/') + 1);

            if (!matchesExtensionFilter(filename)) continue;
            if (filename.endsWith(".md")) continue;

            // Skip invalid/truncated test files
            if (filename.contains("truncated") || filename.equals("random.bin")) {
                continue;
            }

            // Convert project-relative path to classpath-relative path
            // index.txt has: stb-image-decoder/src/test/resources/testData/image/xxx.png
            // we need: testData/image/xxx.png
            String classpathPath = imagePath;
            if (classpathPath.startsWith("stb-image-decoder/src/test/resources/")) {
                classpathPath = classpathPath.substring("stb-image-decoder/src/test/resources/".length());
            }

            try {
                ByteBuffer image = loadResource(classpathPath);
                StbImageResult result = stbImage.getDecoder(image,false).load(4);

                assertNotNull(result, "Failed to load: " + filename);
                assertTrue(result.getWidth() > 0, "Invalid width for: " + filename);
                assertTrue(result.getHeight() > 0, "Invalid height for: " + filename);
                assertTrue(result.getChannels() > 0, "Invalid channels for: " + filename);

                loaded++;
            } catch (Exception e) {
                failed++;
                failedImages.add(filename + ": " + e.getMessage());
            }
        }

        System.out.println("Loaded: " + loaded + ", Failed: " + failed);
        if (!failedImages.isEmpty()) {
            System.out.println("Failed images:");
            for (String f : failedImages) {
                System.out.println("  - " + f);
            }
        }
        assertTrue(failed == 0, "Some images failed to load: " + failedImages);
    }

    /**
     * Test each image against reference binary data
     */
    @Test
    void testAllImagesAgainstReference() throws IOException {
        int limit = testLimit("TEST_REF_LIMIT", Integer.MAX_VALUE);

        int passed = 0;
        int failed = 0;
        List<String> failedImages = new ArrayList<>();

        int count = 0;
        for (String imagePath : imagePaths) {
            if (count++ >= limit) break;
            String filename = imagePath.substring(imagePath.lastIndexOf('/') + 1);
            String refFilename = filename + ".bin";

            if (!matchesExtensionFilter(filename)) continue;
            if (filename.endsWith(".md")) continue;

            // Skip files without reference data or known problematic ones
            if (filename.contains("truncated") || filename.equals("random.bin")) {
                continue;
            }
           

            // Convert project-relative path to classpath-relative path
            String classpathPath = imagePath;
            if (classpathPath.startsWith("stb-image-decoder/src/test/resources/")) {
                classpathPath = classpathPath.substring("stb-image-decoder/src/test/resources/".length());
            }

            try {
                // Load reference
                int[] ref = loadReference(refFilename);
                int expectedChannels = ref[2];

                // Request the same output channel count used by the reference generator.
                ByteBuffer image = loadResource(classpathPath);
                StbImageResult result = stbImage.getDecoder(image, false).load(expectedChannels);

                assertNotNull(result, "Failed to load: " + filename);

                // Compare dimensions
                assertEquals(ref[0], result.getWidth(), "Width mismatch: " + filename);
                assertEquals(ref[1], result.getHeight(), "Height mismatch: " + filename);


                int resultChannels = result.getChannels();
                assertEquals(expectedChannels, resultChannels, "Channel mismatch: " + filename);
                assertPixelsMatch(result.getData(), ref, ref[0], ref[1], expectedChannels, result.isHdr());

                passed++;
            } catch (AssertionError e) {
                failed++;
                failedImages.add(filename + ": " + e.getMessage());
            } catch (Exception e) {
                failed++;
                failedImages.add(filename + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (!failedImages.isEmpty()) {
            System.out.println("Failed images:");
            for (int i = 0; i < failedImages.size(); i++) {
                System.out.println("  - " + failedImages.get(i));
            }
           
        }
        assertTrue(failed == 0, "Some images failed comparison: " + failedImages.size() + " failures");
    }

    // ==================== Format Detection Tests ====================

    @Test
    void testPngDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/rgba8.png");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.PNG, info.getFormat());
    }

    @Test
    void testBmpDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/rgba32.bmp");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.BMP, info.getFormat());
    }

    @Test
    void testTgaDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/rgba_rle.tga");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.TGA, info.getFormat());
    }

    @Test
    void testJpegDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/rgb_baseline.jpg");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.JPEG, info.getFormat());
    }

    @Test
    void testPnmDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/rgb.ppm");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.PNM, info.getFormat());
    }

    @Test
    void testGifDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/single.gif");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.GIF, info.getFormat());
    }

    @Test
    void testPsdDetection() throws IOException {
        ByteBuffer image = loadResource("testData/image/rgb.psd");
        StbImageInfo info = stbImage.getDecoder(image, false).info();
        assertNotNull(info);
        assertEquals(StbImageInfo.ImageFormat.PSD, info.getFormat());
    }
}
