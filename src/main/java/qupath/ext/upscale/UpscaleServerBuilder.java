package qupath.ext.upscale;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.ResourceBundle;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

/**
 * Describes how to rebuild an {@link UpscaleImageServer}: the builder of the source image, the model,
 * the processed channels, the tile settings and the 8-bit channel ranges.
 * <p>
 * This is what gets written (as JSON) into a project entry. No pixels are stored.
 * <ul>
 *   <li>The model is stored by identifier (e.g. {@code edsr_x4}), not by path, and is looked up in the model
 *       folder of the machine that opens the project.</li>
 *   <li>The backend (CPU or GPU) is not stored: it is a property of the machine, so the backend last
 *       used on that machine is used, falling back to the CPU if it is not available.</li>
 *   <li>The channel ranges are stored, so the result does not depend on re-estimating them.</li>
 * </ul>
 * Builders should be immutable and lightweight; this one is both.
 */
public class UpscaleServerBuilder implements ImageServerBuilder.ServerBuilder<BufferedImage> {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.upscale.ui.strings");

    private final ImageServerBuilder.ServerBuilder<BufferedImage> source;
    private final String modelId;
    private final int[] bands;
    private final int pad;
    private final int tile;
    private final float[] lo;
    private final float[] hi;

    /**
     * Create a builder.
     * @param source builder for the image that is upscaled
     * @param modelId identifier of the model, see {@link UpscaleModel#getId()}
     * @param bands indices of the processed source channels (empty for RGB images)
     * @param pad tile overlap, in source pixels
     * @param tile preferred output tile size
     * @param lo per-channel lower end of the 8-bit mapping (empty for RGB images)
     * @param hi per-channel upper end of the 8-bit mapping (empty for RGB images)
     */
    public UpscaleServerBuilder(ImageServerBuilder.ServerBuilder<BufferedImage> source, String modelId,
                                int[] bands, int pad, int tile, float[] lo, float[] hi) {
        this.source = source;
        this.modelId = modelId;
        this.bands = bands.clone();
        this.pad = pad;
        this.tile = tile;
        this.lo = lo.clone();
        this.hi = hi.clone();
    }

    @Override
    public ImageServer<BufferedImage> build() throws Exception {
        String folder = UpscaleExtension.modelFolderProperty().get();
        UpscaleModel model = ModelLibrary.findById(ModelLibrary.available(folder), modelId)
                .orElseThrow(() -> new IOException(
                        String.format(resources.getString("error.model-unavailable"), modelId, folder)));
        Backend backend = Backend.resolve(UpscaleExtension.backendProperty().get());
        ImageServer<BufferedImage> sourceServer = source.build();
        try {
            return new UpscaleImageServer(sourceServer, model, backend, bands, pad, tile, lo, hi);
        } catch (Throwable t) {
            // Do not leave the source image open if the upscaled server cannot be created
            try {
                sourceServer.close();
            } catch (Exception e) {
                t.addSuppressed(e);
            }
            throw t;
        }
    }

    @Override
    public Collection<URI> getURIs() {
        return source.getURIs();
    }

    @Override
    public ImageServerBuilder.ServerBuilder<BufferedImage> updateURIs(Map<URI, URI> updateMap) {
        ImageServerBuilder.ServerBuilder<BufferedImage> updated = source.updateURIs(updateMap);
        if (updated == source)
            return this;
        return new UpscaleServerBuilder(updated, modelId, bands, pad, tile, lo, hi);
    }

    @Override
    public String toString() {
        return "Upscale (" + modelId + ") of " + source;
    }
}
