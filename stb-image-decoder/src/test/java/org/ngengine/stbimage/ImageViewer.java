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
            "Supported Images", "jpg", "jpeg", "png", "bmp", "tga", "gif", "ppm", "pgm", "pbm", "pn", "psd"));

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            loadImage(chooser.getSelectedFile().toPath());
        }
    }

    private void loadImage(Path path) {
        try {
            statusLabel.setText("Loading...");
            formatLabel.setText("Format: -");
            sizeLabel.setText("Size: -");

            byte[] data = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.wrap(data);

            // Get info first
            StbImageInfo info = stbImage.getDecoder(buffer, false).info();
            if (info == null) {
                showError("Failed to load image: unknown format");
                return;
            }

            formatLabel.setText("Format: " + info.getFormat());
            sizeLabel.setText("Size: " + info.getWidth() + " x " + info.getHeight());

            // Load full image with 4 channels (RGBA)
            buffer.rewind();
            StbImageResult result = stbImage.getDecoder(buffer, false).load(4);

            // Convert to BufferedImage
            BufferedImage img = createBufferedImage(result);

            // Display
            ImageIcon icon = new ImageIcon(img);
            imageLabel.setIcon(icon);
            imageLabel.setText("");

            statusLabel.setText("Loaded: " + path.getFileName() + " (" +
                result.getWidth() + "x" + result.getHeight() + ", " +
                result.getChannels() + " channels)");

        } catch (Exception e) {
            showError("Error loading image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private BufferedImage createBufferedImage(StbImageResult result) {
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
