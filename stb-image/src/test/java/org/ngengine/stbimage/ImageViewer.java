package org.ngengine.stbimage;

import javax.swing.*;

import org.ngengine.stbimage.StbImage;
import org.ngengine.stbimage.StbImageInfo;
import org.ngengine.stbimage.StbImageResult;

import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.file.*;

/**
 * A simple AWT-based image viewer for testing image decoding.
 * Usage: Run as Java application, then use File > Open to load images.
 */
public class ImageViewer extends JFrame {

    private JMenuBar menuBar;
    private JMenu fileMenu;
    private JMenuItem openItem;
    private JMenuItem exitItem;
    private JLabel imageLabel;
    private JPanel statusPanel;
    private JLabel statusLabel;
    private JLabel formatLabel;
    private JLabel sizeLabel;
    private StbImage stbImage;
    private Timer animationTimer;
    private GifDecoder animatedGifDecoder;

    public ImageViewer() {
        super("STB Image Viewer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);

        // Initialize STB
        stbImage = new StbImage();

        // Create UI
        createMenu();
        createContent();
        createStatusBar();

        // Enable drag-and-drop onto the image area
        enableDragAndDrop();

        setLocationRelativeTo(null);
    }

    private void createMenu() {
        menuBar = new JMenuBar();

        fileMenu = new JMenu("File");
        openItem = new JMenuItem("Open...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openFile());

        exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(openItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);

        setJMenuBar(menuBar);
    }

    private void createContent() {
        imageLabel = new JLabel("", SwingConstants.CENTER);
        imageLabel.setPreferredSize(new Dimension(800, 500));

        add(new JScrollPane(imageLabel), BorderLayout.CENTER);
    }

    private void createStatusBar() {
        statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBorder(BorderFactory.createEtchedBorder());

        formatLabel = new JLabel("Format: -");
        sizeLabel = new JLabel("Size: -");
        statusLabel = new JLabel("Ready");

        statusPanel.add(formatLabel);
        statusPanel.add(Box.createHorizontalStrut(20));
        statusPanel.add(sizeLabel);
        statusPanel.add(Box.createHorizontalStrut(20));
        statusPanel.add(statusLabel);

        add(statusPanel, BorderLayout.SOUTH);
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser(".");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        // Add file filter for supported images
        chooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Supported Images", "jpg", "jpeg", "png", "bmp", "tga", "gif", "ppm", "pgm", "pbm", "pn", "psd", "hdr"));

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            loadImage(chooser.getSelectedFile().toPath());
        }
    }

    private void loadImage(Path path) {
        try {
            stopAnimation();
            statusLabel.setText("Loading...");
            formatLabel.setText("Format: -");
            sizeLabel.setText("Size: -");

            byte[] data = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.wrap(data);

            // Get info first
            StbDecoder infoDecoder = stbImage.getDecoder(buffer, false);
            StbImageInfo info = infoDecoder.info();
            if (info == null) {
                showError("Failed to load image: unknown format");
                return;
            }

            formatLabel.setText("Format: " + info.getFormat());
            sizeLabel.setText("Size: " + info.getWidth() + " x " + info.getHeight());

            // Load full image with 4 channels (RGBA)
            StbDecoder decoder = stbImage.getDecoder(ByteBuffer.wrap(data), false);
            StbImageResult result = decoder.load(4);

            // Convert to BufferedImage
            BufferedImage img = createBufferedImage(result);
            showImage(img);

            statusLabel.setText("Loaded: " + path.getFileName() + " (" +
                result.getWidth() + "x" + result.getHeight() + ", " +
                result.getChannels() + " channels)");

            if (decoder instanceof GifDecoder) {
                GifDecoder gifDecoder = (GifDecoder) decoder;
                if (gifDecoder.isAnimated()) {
                    int choice = JOptionPane.showConfirmDialog(
                        this,
                        "This GIF is animated. Preload all frames now?",
                        "Animated GIF",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    );
                    if (choice == JOptionPane.YES_OPTION) {
                        gifDecoder.loadAllFrames(4);
                    }
                    animatedGifDecoder = gifDecoder;
                    startGifAnimation(path.getFileName().toString());
                }
            }

        } catch (Exception e) {
            showError("Error loading image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showImage(BufferedImage img) {
        imageLabel.setIcon(new ImageIcon(img));
        imageLabel.setText("");
    }

    private void stopAnimation() {
        animatedGifDecoder = null;
        if (animationTimer != null) {
            animationTimer.stop();
            animationTimer = null;
        }
    }

    private void startGifAnimation(String fileName) {
        if (animatedGifDecoder == null || animatedGifDecoder.getFrameCount() <= 1) {
            return;
        }
        int initialDelay = animatedGifDecoder.getLastFrameDelayMs();
        if (initialDelay <= 0) initialDelay = 100;

        animationTimer = new Timer(Math.max(20, initialDelay), e -> {
            try {
                if (animatedGifDecoder == null) {
                    stopAnimation();
                    return;
                }
                StbImageResult frame = animatedGifDecoder.loadNextFrame(4);
                showImage(createBufferedImage(frame));
                int d = animatedGifDecoder.getLastFrameDelayMs();
                if (d <= 0) d = 100;
                animationTimer.setDelay(Math.max(20, d));
                statusLabel.setText("Playing: " + fileName + " (" + animatedGifDecoder.getFrameCount() + " frames)");
            } catch (Exception ex) {
                stopAnimation();
                showError("Error animating GIF: " + ex.getMessage());
            }
        });
        animationTimer.start();
    }

    private BufferedImage createBufferedImage(StbImageResult result) {
        if (result.isHdr()) {
            return createBufferedImageHdr(result);
        }
        if (result.is16Bit()) {
            return createBufferedImage16(result);
        }

        int width = result.getWidth();
        int height = result.getHeight();
        int channels = result.getChannels();

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteBuffer data = result.getData();
        data.rewind();

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * channels;

                int r, g, b, a;
                if (channels == 1) {
                    r = g = b = data.get(offset) & 0xFF;
                    a = 255;
                } else if (channels == 2) {
                    r = g = b = data.get(offset) & 0xFF;
                    a = data.get(offset + 1) & 0xFF;
                } else if (channels == 3) {
                    r = data.get(offset) & 0xFF;
                    g = data.get(offset + 1) & 0xFF;
                    b = data.get(offset + 2) & 0xFF;
                    a = 255;
                } else { // 4 channels
                    r = data.get(offset) & 0xFF;
                    g = data.get(offset + 1) & 0xFF;
                    b = data.get(offset + 2) & 0xFF;
                    a = data.get(offset + 3) & 0xFF;
                }

                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private BufferedImage createBufferedImage16(StbImageResult result) {
        int width = result.getWidth();
        int height = result.getHeight();
        int channels = result.getChannels();

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteBuffer data = result.getData();
        data.rewind();

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * channels * 2;
                int r, g, b, a;
                if (channels == 1) {
                    r = g = b = to8bitFrom16(data.getShort(offset));
                    a = 255;
                } else if (channels == 2) {
                    r = g = b = to8bitFrom16(data.getShort(offset));
                    a = to8bitFrom16(data.getShort(offset + 2));
                } else if (channels == 3) {
                    r = to8bitFrom16(data.getShort(offset));
                    g = to8bitFrom16(data.getShort(offset + 2));
                    b = to8bitFrom16(data.getShort(offset + 4));
                    a = 255;
                } else {
                    r = to8bitFrom16(data.getShort(offset));
                    g = to8bitFrom16(data.getShort(offset + 2));
                    b = to8bitFrom16(data.getShort(offset + 4));
                    a = to8bitFrom16(data.getShort(offset + 6));
                }
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private BufferedImage createBufferedImageHdr(StbImageResult result) {
        int width = result.getWidth();
        int height = result.getHeight();
        int channels = result.getChannels();

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteBuffer data = result.getData();
        data.rewind();

        int pixelCount = width * height;
        float[] linear = new float[pixelCount * 4];
        float maxLuma = 0.0f;

        for (int i = 0; i < pixelCount; i++) {
            int off = i * channels * 4;
            float r, g, b, a;
            if (channels == 1) {
                r = g = b = data.getFloat(off);
                a = 1.0f;
            } else if (channels == 2) {
                r = g = b = data.getFloat(off);
                a = data.getFloat(off + 4);
            } else if (channels == 3) {
                r = data.getFloat(off);
                g = data.getFloat(off + 4);
                b = data.getFloat(off + 8);
                a = 1.0f;
            } else {
                r = data.getFloat(off);
                g = data.getFloat(off + 4);
                b = data.getFloat(off + 8);
                a = data.getFloat(off + 12);
            }

            int lo = i * 4;
            linear[lo] = r;
            linear[lo + 1] = g;
            linear[lo + 2] = b;
            linear[lo + 3] = a;
            float luma = 0.2126f * Math.max(r, 0.0f) + 0.7152f * Math.max(g, 0.0f) + 0.0722f * Math.max(b, 0.0f);
            maxLuma = Math.max(maxLuma, luma);
        }

        float exposure = maxLuma > 0.0f ? 0.8f / maxLuma : 1.0f;
        int[] pixels = new int[pixelCount];
        for (int i = 0; i < pixelCount; i++) {
            int lo = i * 4;
            int r = to8bitHdr(linear[lo], exposure);
            int g = to8bitHdr(linear[lo + 1], exposure);
            int b = to8bitHdr(linear[lo + 2], exposure);
            int a = to8bitAlpha(linear[lo + 3]);
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private int to8bitFrom16(short value) {
        return (Short.toUnsignedInt(value) * 255 + 32767) / 65535;
    }

    private int to8bitHdr(float linear, float exposure) {
        float x = Math.max(0.0f, linear * exposure);
        float mapped = x / (1.0f + x); // Reinhard tone map
        float gamma = (float) Math.pow(mapped, 1.0 / 2.2);
        int out = Math.round(gamma * 255.0f);
        return Math.max(0, Math.min(255, out));
    }

    private int to8bitAlpha(float alpha) {
        int out = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return Math.max(0, Math.min(255, out));
    }

    private void enableDragAndDrop() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                Transferable t = support.getTransferable();
                try {
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) {
                            loadImage(files.get(0).toPath());
                            return true;
                        }
                    }

                    if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String s = (String) t.getTransferData(DataFlavor.stringFlavor);
                        String first = s.split("\\R")[0].trim();
                        if (first.startsWith("file://")) {
                            java.net.URI uri = new java.net.URI(first);
                            loadImage(Paths.get(uri));
                        } else {
                            loadImage(Paths.get(first));
                        }
                        return true;
                    }
                } catch (Exception e) {
                    showError("Error importing dropped file: " + e.getMessage());
                    e.printStackTrace();
                }
                return false;
            }
        };

        imageLabel.setTransferHandler(handler);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
        statusLabel.setText("Error: " + message);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Set look and feel
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Ignore
            }

            ImageViewer viewer = new ImageViewer();
            viewer.setVisible(true);
        });
    }
}
