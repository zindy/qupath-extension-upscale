package qupath.ext.upscale;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bytedeco.opencv.global.opencv_core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;

/**
 * Applies and removes the upscaled view of the image in the active viewer.
 * <p>
 * The original {@link ImageData} is kept so that "Reset view" can put it back exactly as it was.
 * The upscaled view is a separate {@link ImageData} with its own (empty) object hierarchy.
 */
public class UpscaleManager {

    private static final Logger logger = LoggerFactory.getLogger(UpscaleManager.class);
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.upscale.ui.strings");

    private final QuPathGUI qupath;

    /** Upscaled image data -> the original it was made from. */
    private final Map<ImageData<BufferedImage>, ImageData<BufferedImage>> originals =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Server creation reads a thumbnail and loads the model, so keep it off the JavaFX thread. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "upscale-setup");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Create a manager.
     * @param qupath the running QuPath instance
     */
    public UpscaleManager(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Upscale the image in the active viewer and show the result there.
     * Must be called on the JavaFX thread.
     * @param model the model (or bicubic control) to use
     * @param backend where to run the network (ignored for the bicubic control)
     * @param displayedOnly if true, only the channels currently displayed are processed; otherwise all channels
     *                      (ignored for RGB images, which are upscaled as a whole)
     */
    public void enhance(UpscaleModel model, Backend backend, boolean displayedOnly) {
        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> current = viewer == null ? null : viewer.getImageData();
        if (current == null) {
            showError(resources.getString("error.no-image"));
            return;
        }

        // If the viewer already shows an upscaled image, start again from its original
        ImageData<BufferedImage> original = originals.getOrDefault(current, current);
        ImageServer<BufferedImage> server = original.getServer();
        if (!model.isBicubic() && (model.getFile() == null || !Files.isRegularFile(model.getFile()))) {
            showError(String.format(resources.getString("error.model-missing"), model.getFile()));
            return;
        }

        boolean rgb = server.isRGB();
        int[] bands = rgb ? new int[0] : selectBands(server, viewer.getImageDisplay(), displayedOnly);
        if (!rgb && bands.length == 0) {
            showError(resources.getString("error.no-channels"));
            return;
        }
        Backend effective = model.isBicubic() ? Backend.CPU : backend;

        int pad = UpscaleExtension.padProperty().get();
        int tile = UpscaleExtension.tileProperty().get();
        int threads = UpscaleExtension.threadsProperty().get();

        String what = rgb ? resources.getString("info.rgb")
                : String.format(resources.getString("info.channels"), bands.length);
        Dialogs.showInfoNotification(resources.getString("title"),
                String.format(resources.getString("info.preparing"),
                        model.getDisplayName(), effective.getDisplayName(), what));

        executor.submit(() -> {
            try {
                if (threads > 0)
                    opencv_core.setNumThreads(threads);
                // Loads the network and runs it once on a dummy image, so failures show up here
                UpscaleImageServer upscaled = new UpscaleImageServer(server, model, effective, bands, pad, tile);
                ImageData<BufferedImage> upscaledData = new ImageData<>(upscaled, original.getImageType());
                // Brightfield images need their stain vectors (colour deconvolution) to carry over
                var stains = original.getColorDeconvolutionStains();
                if (stains != null)
                    upscaledData.setColorDeconvolutionStains(stains);
                Platform.runLater(() -> show(viewer, current, original, upscaledData));
            } catch (Throwable t) {
                logger.error("Unable to create the upscaled image", t);
                String message = String.format(resources.getString("error.failed"), String.valueOf(t));
                if (effective.isGpu())
                    message += "\n\n" + resources.getString("error.failed.gpu");
                String finalMessage = message;
                Platform.runLater(() -> showError(finalMessage));
            }
        });
    }

    /**
     * Put the original image back in the active viewer.
     * Must be called on the JavaFX thread.
     */
    public void reset() {
        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> current = viewer == null ? null : viewer.getImageData();
        ImageData<BufferedImage> original = current == null ? null : originals.remove(current);
        if (original == null) {
            Dialogs.showInfoNotification(resources.getString("title"), resources.getString("info.not-upscaled"));
            return;
        }
        if (!setViewerImage(viewer, original))
            originals.put(current, original); // still showing the upscaled view, so keep the link to the original
    }

    /**
     * Add the upscaled image in the active viewer to the current project as a new entry, so that it survives
     * restarting QuPath. The entry stores how to rebuild the image (source image, model, channels and ranges),
     * not the pixels, together with any objects drawn on it.
     * Must be called on the JavaFX thread.
     */
    public void addToProject() {
        QuPathViewer viewer = qupath.getViewer();
        ImageData<BufferedImage> current = viewer == null ? null : viewer.getImageData();
        if (!isUpscaled(current)) {
            Dialogs.showInfoNotification(resources.getString("title"), resources.getString("info.not-upscaled"));
            return;
        }
        var project = qupath.getProject();
        if (project == null) {
            showError(resources.getString("error.no-project"));
            return;
        }
        try {
            ImageServer<BufferedImage> server = current.getServer();
            var entry = project.addImage(server.getBuilder());
            entry.setImageName(server.getMetadata().getName());
            entry.saveImageData(current);
            project.syncChanges();
            qupath.refreshProject();
            Dialogs.showInfoNotification(resources.getString("title"),
                    String.format(resources.getString("info.added-to-project"), entry.getImageName()));
        } catch (Exception e) {
            logger.error("Unable to add the upscaled image to the project", e);
            showError(String.format(resources.getString("error.add-to-project-failed"), e.getMessage()));
        }
    }

    /**
     * Check whether the image data is an upscaled view created by this extension.
     * @param imageData the image data to check
     * @return true if it is an upscaled view
     */
    public boolean isUpscaled(ImageData<BufferedImage> imageData) {
        return imageData != null && originals.containsKey(imageData);
    }

    private void show(QuPathViewer viewer, ImageData<BufferedImage> current,
                      ImageData<BufferedImage> original, ImageData<BufferedImage> upscaledData) {
        // If the user moved on to another image while the server was being prepared, do nothing
        if (viewer.getImageData() != current) {
            logger.debug("Viewer image changed while preparing the upscaled view - discarding it");
            return;
        }
        originals.remove(current);
        originals.put(upscaledData, original);
        if (!setViewerImage(viewer, upscaledData)) {
            // Undo: the viewer still shows what it showed before
            originals.remove(upscaledData);
            if (original != current)
                originals.put(current, original);
        }
    }

    /**
     * Set the image data of a viewer. QuPath declares an IOException for this, so report it rather than throw.
     * @return true if the viewer now shows the image data
     */
    private static boolean setViewerImage(QuPathViewer viewer, ImageData<BufferedImage> imageData) {
        try {
            viewer.setImageData(imageData);
            return true;
        } catch (IOException e) {
            logger.error("Unable to set the image in the viewer", e);
            showError(String.format(resources.getString("error.set-image-failed"), e.getMessage()));
            return false;
        }
    }

    /**
     * Choose which channels of the server to process.
     * Displayed channels are matched to server channels by name first, then by position.
     */
    private static int[] selectBands(ImageServer<BufferedImage> server, ImageDisplay display, boolean displayedOnly) {
        List<ImageChannel> channels = server.getMetadata().getChannels();
        List<Integer> selected = new ArrayList<>();
        if (!displayedOnly || display == null) {
            for (int i = 0; i < channels.size(); i++)
                selected.add(i);
        } else {
            List<ChannelDisplayInfo> available = new ArrayList<>(display.availableChannels());
            for (ChannelDisplayInfo info : new ArrayList<>(display.selectedChannels())) {
                int index = indexOfName(channels, info.getName());
                if (index < 0 && available.size() == channels.size())
                    index = available.indexOf(info);
                if (index >= 0 && index < channels.size() && !selected.contains(index))
                    selected.add(index);
            }
        }
        selected.sort(null);
        int[] bands = new int[selected.size()];
        for (int i = 0; i < bands.length; i++)
            bands[i] = selected.get(i);
        return bands;
    }

    private static int indexOfName(List<ImageChannel> channels, String name) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).getName().equals(name))
                return i;
        }
        return -1;
    }

    private static void showError(String message) {
        Dialogs.showErrorMessage(resources.getString("error"), message);
    }
}
