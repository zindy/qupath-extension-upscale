package qupath.ext.upscale;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn_superres.DnnSuperResImpl;

import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/**
 * A tiled image server that wraps another server and upscales it on the fly,
 * either with a dnn_superres network or with bicubic interpolation (as a control).
 * <p>
 * Notes:
 * <ul>
 *   <li>The output has {@code scale} times the pixel dimensions, and pixel size divided by {@code scale}.</li>
 *   <li><b>RGB sources</b> are upscaled directly: the 8-bit image goes through the network once, which is what
 *       dnn_superres expects (EDSR works on the colour image; ESPCN, FSRCNN and LapSRN work on luminance and
 *       interpolate chrominance).</li>
 *   <li><b>Multichannel sources</b>: only the selected channels are present in the output (names and colours are
 *       kept). The networks only take 8-bit 3-channel input, so each channel is mapped to 8 bits using a fixed
 *       range, replicated to 3 channels, upscaled, then mapped back. This path is for visualisation and
 *       segmentation experiments, not for quantification.</li>
 *   <li>Level 0 (downsample 1) is upscaled. Levels with a downsample of at least {@code scale}
 *       are read directly from the source.</li>
 *   <li>On the CPU each thread gets its own network instance, so tiles are processed in parallel.
 *       On a GPU backend a single instance is shared and calls are serialised, because several copies of the
 *       network on one device only add memory use and contention.</li>
 * </ul>
 */
public class UpscaleImageServer extends AbstractTileableImageServer {

    /** Size of the dummy image used to check that the network really runs. */
    private static final int WARMUP_SIZE = 32;

    private final ImageServer<BufferedImage> source;
    private final UpscaleModel model;
    private final Backend backend;
    private final boolean rgb;
    private final int scale;
    private final int pad;
    private final int tile;
    private final int[] bands;
    private final float[] lo;
    private final float[] hi;
    private final boolean integerType;
    private final ColorModel outCm;
    private final ImageServerMetadata metadata;
    private final String serverId;
    private final ThreadLocal<DnnSuperResImpl> threadLocalModel;
    private final DnnSuperResImpl sharedModel;
    private final Object modelLock = new Object();
    private volatile boolean closed = false;

    /**
     * Create a new server. The network is loaded and run once on a dummy image, so a missing file, missing
     * native library or failing backend is reported here rather than while tiles are being read.
     * @param source the server to upscale
     * @param model the model (or bicubic control) to use
     * @param backend where to run the network; ignored for the bicubic control
     * @param bands indices of the source channels to process, in ascending order; ignored for RGB sources
     * @param pad overlap (in source pixels) added around each tile to avoid seams
     * @param tile preferred output tile size; adjusted to be a multiple of the scale
     */
    public UpscaleImageServer(ImageServer<BufferedImage> source, UpscaleModel model, Backend backend,
                              int[] bands, int pad, int tile) {
        this(source, model, backend, bands, pad, tile, null, null);
    }

    /**
     * Create a new server with known channel ranges. This is used when the server is rebuilt from a project
     * entry, so that the result does not depend on re-estimating the ranges.
     * @param source the server to upscale
     * @param model the model (or bicubic control) to use
     * @param backend where to run the network; ignored for the bicubic control
     * @param bands indices of the source channels to process, in ascending order; ignored for RGB sources
     * @param pad overlap (in source pixels) added around each tile to avoid seams
     * @param tile preferred output tile size; adjusted to be a multiple of the scale
     * @param loIn per-band lower end of the 8-bit mapping, or null to estimate it from the image
     * @param hiIn per-band upper end of the 8-bit mapping, or null to estimate it from the image
     */
    public UpscaleImageServer(ImageServer<BufferedImage> source, UpscaleModel model, Backend backend,
                              int[] bands, int pad, int tile, float[] loIn, float[] hiIn) {
        super();
        this.source = source;
        this.model = model;
        this.backend = model.isBicubic() ? Backend.CPU : backend;
        this.rgb = source.isRGB();
        this.scale = model.getScale();
        this.pad = Math.max(0, pad);
        // Tile origins must be divisible by the scale, so the tile size must be a multiple of it
        this.tile = Math.max(scale * 32, tile - (tile % scale));
        this.bands = rgb ? new int[0] : bands.clone();
        this.integerType = !source.getPixelType().isFloatingPoint();

        final UpscaleModel m = model;
        final Backend b = this.backend;
        this.threadLocalModel = ThreadLocal.withInitial(() -> createNativeModel(m, b));

        if (rgb) {
            // Already 8-bit: no range mapping needed
            this.lo = new float[0];
            this.hi = new float[0];
        } else if (loIn != null && hiIn != null
                && loIn.length == this.bands.length && hiIn.length == this.bands.length) {
            this.lo = loIn.clone();
            this.hi = hiIn.clone();
        } else {
            float[][] ranges = computeRanges(source, this.bands);
            this.lo = ranges[0];
            this.hi = ranges[1];
        }

        this.serverId = "Upscale:" + source.getPath() + ":" + model.getId() + ":" + this.backend.name()
                + ":" + Arrays.toString(this.bands) + ":p" + this.pad + ":t" + this.tile;

        int width = source.getWidth() * scale;
        int height = source.getHeight() * scale;
        List<Double> downsamples = new ArrayList<>();
        downsamples.add(1.0);
        double d = scale;
        while (true) {
            downsamples.add(d);
            if (Math.max(width, height) / d <= this.tile)
                break;
            d *= 2;
        }
        double[] downsampleArray = new double[downsamples.size()];
        for (int i = 0; i < downsampleArray.length; i++)
            downsampleArray[i] = downsamples.get(i);

        ImageServerMetadata originalMetadata = source.getMetadata();
        var cal = originalMetadata.getPixelCalibration();
        var builder = new ImageServerMetadata.Builder(originalMetadata)
                .width(width)
                .height(height)
                .pixelSizeMicrons(cal.getPixelWidthMicrons() / scale, cal.getPixelHeightMicrons() / scale)
                .preferredTileSize(this.tile, this.tile)
                .levelsFromDownsamples(downsampleArray)
                .name(originalMetadata.getName() + " [" + model.getDisplayName() + "]");
        if (rgb) {
            // Keep the RGB flag and channels of the source
            this.outCm = null;
        } else {
            List<ImageChannel> selected = new ArrayList<>();
            for (int band : this.bands)
                selected.add(originalMetadata.getChannel(band));
            this.outCm = ColorModelFactory.createColorModel(source.getPixelType(), selected);
            builder.channels(selected);
        }
        this.metadata = builder.build();

        // Last, so that nothing after this can throw and leave a native network open
        this.sharedModel = (!model.isBicubic() && this.backend.isGpu())
                ? createNativeModel(model, this.backend) : null;
        try {
            warmUp();
        } catch (RuntimeException | Error e) {
            if (sharedModel != null)
                sharedModel.close();
            throw e;
        }
    }

    private static DnnSuperResImpl createNativeModel(UpscaleModel model, Backend backend) {
        if (model.isBicubic())
            return null;
        DnnSuperResImpl sr = new DnnSuperResImpl();
        sr.readModel(model.getFile().toString());
        sr.setModel(model.getAlgorithm(), model.getScale());
        backend.apply(sr);
        return sr;
    }

    /**
     * OpenCV builds the network lazily, so a missing file or an unusable backend only shows up
     * at the first forward pass. Do that pass now, on a dummy image.
     */
    private void warmUp() {
        if (model.isBicubic())
            return;
        try (Scalar zero = new Scalar(0.0);
             Mat in = new Mat(WARMUP_SIZE, WARMUP_SIZE, opencv_core.CV_8UC3, zero);
             Mat out = new Mat()) {
            runModel(in, out);
            if (out.empty() || out.cols() != WARMUP_SIZE * scale || out.rows() != WARMUP_SIZE * scale)
                throw new IllegalStateException("Unexpected output size from " + model.getDisplayName());
        }
    }

    /**
     * Fixed per-channel ranges, so that all tiles are mapped to 8 bits in the same way.
     * The low end is min(0, data minimum); the high end is 1.25 x the 99.9th percentile of the non-zero pixels.
     * @return {lo, hi}, each with one value per band
     */
    private static float[][] computeRanges(ImageServer<BufferedImage> src, int[] bands) {
        int w0 = src.getWidth();
        int h0 = src.getHeight();
        double ds = Math.max(1.0, Math.max(w0, h0) / 2048.0);
        BufferedImage img;
        try {
            img = src.readRegion(RegionRequest.createInstance(src.getPath(), ds, 0, 0, w0, h0));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the image to estimate channel ranges", e);
        }
        Raster r = img.getRaster();
        int w = r.getWidth();
        int h = r.getHeight();
        float[] lo = new float[bands.length];
        float[] hi = new float[bands.length];
        float[] buf = null;
        for (int j = 0; j < bands.length; j++) {
            buf = r.getSamples(0, 0, w, h, bands[j], buf);
            float[] nz = new float[buf.length];
            int n = 0;
            float mn = 0f;
            for (float v : buf) {
                if (v > 0)
                    nz[n++] = v;
                else if (v < mn)
                    mn = v;
            }
            lo[j] = mn;
            if (n == 0) {
                hi[j] = mn + 1f;
                continue;
            }
            Arrays.sort(nz, 0, n);
            int idx = (int) Math.min(n - 1, Math.floor(n * 0.999d));
            hi[j] = Math.max(nz[idx] * 1.25f, mn + 1e-6f);
        }
        return new float[][]{lo, hi};
    }

    /**
     * Run the network (or the bicubic control) on an 8-bit, 3-channel image.
     * GPU backends share one instance, so access is serialised; on the CPU every thread has its own instance.
     */
    private void runModel(Mat in, Mat out) {
        if (model.isBicubic()) {
            try (Size size = new Size(in.cols() * scale, in.rows() * scale)) {
                opencv_imgproc.resize(in, out, size, 0, 0, opencv_imgproc.INTER_CUBIC);
            }
        } else if (sharedModel != null) {
            synchronized (modelLock) {
                if (closed)
                    throw new IllegalStateException("The upscaled image server has been closed");
                sharedModel.upsample(in, out);
            }
        } else {
            if (closed)
                throw new IllegalStateException("The upscaled image server has been closed");
            threadLocalModel.get().upsample(in, out);
        }
    }

    @Override
    protected BufferedImage readTile(TileRequest request) throws IOException {
        RegionRequest rr = request.getRegionRequest();
        double d = rr.getDownsample();

        int x0 = rr.getX() / scale;
        int y0 = rr.getY() / scale;
        int w0 = rr.getWidth() / scale;
        int h0 = rr.getHeight() / scale;

        // Viewing at or below the source resolution: no upscaling needed, just pick the channels
        if (d >= scale) {
            BufferedImage img = source.readRegion(RegionRequest.createInstance(
                    source.getPath(), d / scale, x0, y0, w0, h0, rr.getZ(), rr.getT()));
            return rgb ? img : subsetBands(img);
        }

        // Padded source region, clamped to the image
        int width = source.getWidth();
        int height = source.getHeight();
        int px0 = Math.max(0, x0 - pad);
        int py0 = Math.max(0, y0 - pad);
        int px1 = Math.min(width, x0 + w0 + pad);
        int py1 = Math.min(height, y0 + h0 + pad);
        int pw = px1 - px0;
        int ph = py1 - py0;

        BufferedImage padded = source.readRegion(RegionRequest.createInstance(
                source.getPath(), 1.0, px0, py0, pw, ph, rr.getZ(), rr.getT()));

        int cropX = (x0 - px0) * scale;
        int cropY = (y0 - py0) * scale;
        int cw = w0 * scale;
        int ch = h0 * scale;

        if (rgb)
            return upsampleRgb(padded, pw, ph, cropX, cropY, cw, ch);

        Raster raster = padded.getRaster();
        WritableRaster out = outCm.createCompatibleWritableRaster(cw, ch);

        float[] buf = null;
        for (int j = 0; j < bands.length; j++) {
            buf = raster.getSamples(0, 0, pw, ph, bands[j], buf);
            float range = hi[j] - lo[j];
            float k = 255f / range;
            byte[] bytes = new byte[pw * ph];
            for (int i = 0; i < bytes.length; i++) {
                float v = (buf[i] - lo[j]) * k;
                int iv = v < 0f ? 0 : (v > 255f ? 255 : Math.round(v));
                bytes[i] = (byte) iv;
            }
            float[] res = upsampleBand(bytes, pw, ph, cropX, cropY, cw, ch, lo[j], range);
            out.setSamples(0, 0, cw, ch, j, res);
        }
        return new BufferedImage(outCm, out, false, null);
    }

    private BufferedImage subsetBands(BufferedImage img) {
        Raster r = img.getRaster();
        int w = r.getWidth();
        int h = r.getHeight();
        WritableRaster out = outCm.createCompatibleWritableRaster(w, h);
        float[] buf = null;
        for (int j = 0; j < bands.length; j++) {
            buf = r.getSamples(0, 0, w, h, bands[j], buf);
            out.setSamples(0, 0, w, h, j, buf);
        }
        return new BufferedImage(outCm, out, false, null);
    }

    /**
     * RGB path: the real colour image goes through the network once (no per-channel replication).
     */
    private BufferedImage upsampleRgb(BufferedImage padded, int w, int h, int cx, int cy, int cw, int ch) {
        int[] argb = padded.getRGB(0, 0, w, h, null, 0, w);
        // OpenCV expects BGR order
        byte[] bgr = new byte[w * h * 3];
        for (int i = 0, idx = 0; i < argb.length; i++, idx += 3) {
            int p = argb[i];
            bgr[idx] = (byte) p;
            bgr[idx + 1] = (byte) (p >> 8);
            bgr[idx + 2] = (byte) (p >> 16);
        }
        byte[] ob = new byte[cw * ch * 3];
        try (Mat in = new Mat(h, w, opencv_core.CV_8UC3);
             Mat up = new Mat()) {
            in.data().put(bgr);
            runModel(in, up);
            try (Rect roi = new Rect(cx, cy, cw, ch);
                 Mat view = new Mat(up, roi);
                 Mat crop = view.clone()) {
                crop.data().get(ob);
            }
        }
        int[] pixels = new int[cw * ch];
        for (int i = 0, idx = 0; i < pixels.length; i++, idx += 3) {
            int b = ob[idx] & 0xFF;
            int g = ob[idx + 1] & 0xFF;
            int r = ob[idx + 2] & 0xFF;
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, cw, ch, pixels, 0, cw);
        return img;
    }

    private float[] upsampleBand(byte[] gray, int w, int h, int cx, int cy, int cw, int ch,
                                 float loVal, float range) {
        // dnn_superres wants 3-channel 8-bit input: replicate the single channel
        byte[] rgb3 = new byte[w * h * 3];
        for (int i = 0, idx = 0; i < gray.length; i++, idx += 3) {
            byte b = gray[i];
            rgb3[idx] = b;
            rgb3[idx + 1] = b;
            rgb3[idx + 2] = b;
        }
        byte[] ob = new byte[cw * ch];
        try (Mat bgr = new Mat(h, w, opencv_core.CV_8UC3);
             Mat up = new Mat();
             Mat g = new Mat()) {
            bgr.data().put(rgb3);
            runModel(bgr, up);

            opencv_core.extractChannel(up, g, 0);
            try (Rect roi = new Rect(cx, cy, cw, ch);
                 Mat view = new Mat(g, roi);
                 Mat crop = view.clone()) {
                crop.data().get(ob);
            }
        }
        float[] res = new float[cw * ch];
        float k = range / 255f;
        float round = integerType ? 0.5f : 0f;
        for (int i = 0; i < res.length; i++)
            res[i] = (ob[i] & 0xFF) * k + loVal + round;
        return res;
    }

    /** @return the backend actually requested (always {@link Backend#CPU} for the bicubic control) */
    public Backend getBackend() {
        return backend;
    }

    @Override
    public Collection<URI> getURIs() {
        return source.getURIs();
    }

    @Override
    public String getServerType() {
        return "Upscale (" + model.getDisplayName() + ", " + backend.getDisplayName() + ")";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        ImageServerBuilder.ServerBuilder<BufferedImage> sourceBuilder = source.getBuilder();
        if (sourceBuilder == null)
            throw new UnsupportedOperationException(
                    "The source image cannot be rebuilt, so neither can its upscaled version");
        return new UpscaleServerBuilder(sourceBuilder, model.getId(), bands, pad, tile, lo, hi);
    }

    @Override
    protected String createID() {
        return serverId;
    }

    @Override
    public void close() {
        // The wrapped source server is owned by whoever created it; only the shared native network is ours
        synchronized (modelLock) {
            closed = true;
            if (sharedModel != null)
                sharedModel.close();
        }
    }
}
