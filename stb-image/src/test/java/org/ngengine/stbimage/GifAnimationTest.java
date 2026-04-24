package org.ngengine.stbimage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GifAnimationTest {



    private ByteBuffer loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            byte[] data = readAllBytes(is);
            return ByteBuffer.wrap(data);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private int[] loadReference(String refName) throws IOException {
        ByteBuffer ref = loadResource("testData/expected/" + refName);
        ref.rewind();
        ref.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int width = ref.getInt();
        int height = ref.getInt();
        int channels = ref.getInt();

        int[] out = new int[3 + width * height * channels];
        out[0] = width;
        out[1] = height;
        out[2] = channels;
        for (int i = 0; i < width * height * channels; i++) {
            out[3 + i] = Byte.toUnsignedInt(ref.get());
        }
        return out;
    }

    @Test
    void testAnimatedGifFrameByFrame() throws IOException {
        StbImage stb = new StbImage();
        stb.setFillGifFirstFrameBackground(true);
        GifDecoder decoder = (GifDecoder) stb.getDecoder(loadResource("testData/image/animated.gif"), false);

        StbImageResult frame0 = decoder.load(4);
        assertNotNull(frame0);
        assertTrue(decoder.isAnimated());
        assertTrue(decoder.getFrameCount() > 1);
        assertEquals(0, frame0.getFrameIndex());

        StbImageResult frame1 = decoder.loadNextFrame(4);
        assertNotNull(frame1);
        assertEquals(frame0.getWidth(), frame1.getWidth());
        assertEquals(frame0.getHeight(), frame1.getHeight());
        assertEquals(4, frame1.getChannels());
        assertEquals(1, frame1.getFrameIndex());

        ByteBuffer f0 = frame0.getData();
        ByteBuffer f1 = frame1.getData();
        int diffCount = 0;
        for (int i = 0; i < f0.limit(); i++) {
            if ((f0.get(i) & 0xFF) != (f1.get(i) & 0xFF)) {
                diffCount++;
            }
        }
        assertTrue(diffCount > 0, "Animated GIF next frame should differ from first frame");

        for (int i = 0; i < decoder.getFrameCount() * 2; i++) {
            StbImageResult f = decoder.loadNextFrame(4);
            assertNotNull(f);
            assertEquals(frame0.getWidth(), f.getWidth());
            assertEquals(frame0.getHeight(), f.getHeight());
            assertTrue(f.getFrameIndex() >= 0);
            assertTrue(f.getFrameIndex() < decoder.getFrameCount());
        }
    }

    @Test
    void testAnimatedGifFirstFrameParityWithReference() throws IOException {
        StbImage stb = new StbImage();
        stb.setFillGifFirstFrameBackground(true);
        GifDecoder decoder = (GifDecoder) stb.getDecoder(loadResource("testData/image/animated.gif"), false);
        StbImageResult frame0 = decoder.load(4);

        int[] expected = loadReference("animated.gif.bin");
        assertEquals(expected[0], frame0.getWidth());
        assertEquals(expected[1], frame0.getHeight());
        assertEquals(expected[2], frame0.getChannels());

        ByteBuffer data = frame0.getData();
        int offset = 3;
        for (int i = 0; i < frame0.getWidth() * frame0.getHeight() * frame0.getChannels(); i++) {
            int decoded = Byte.toUnsignedInt(data.get(i));
            int diff = Math.abs(decoded - expected[offset + i]);
            assertTrue(diff <= 3, "First frame mismatch at byte " + i + ": decoded=" + decoded + ", expected=" + expected[offset + i]);
        }
    }

    @Test
    void testLoadAllFramesMatchesStreamingFrameCount() throws IOException {
        StbImage stb = new StbImage();
        GifDecoder decoder = (GifDecoder) stb.getDecoder(loadResource("testData/image/animated.gif"), false);

        int expectedCount = decoder.getFrameCount();
        List<StbImageResult> all = decoder.loadAllFrames(4);

        assertEquals(expectedCount, all.size());
        assertFalse(all.isEmpty());
        for (StbImageResult frame : all) {
            assertEquals(4, frame.getChannels());
            assertEquals(all.get(0).getWidth(), frame.getWidth());
            assertEquals(all.get(0).getHeight(), frame.getHeight());
        }
    }

    @Test
    void testGifInfoContainsFrameCount() throws IOException {
        StbImage stb = new StbImage();
        GifDecoder decoder = (GifDecoder) stb.getDecoder(loadResource("testData/image/animated.gif"), false);
        StbImageInfo info = decoder.info();

        assertNotNull(info);
        assertEquals(decoder.getFrameCount(), info.getNumFrames());
        assertTrue(info.getNumFrames() > 1);
    }
}
