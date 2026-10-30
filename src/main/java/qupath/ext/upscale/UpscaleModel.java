package qupath.ext.upscale;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes one way of upscaling: either a dnn_superres model (algorithm + scale + .pb file)
 * or plain bicubic interpolation, which is used as a control.
 * <p>
 * Model files are recognised by their upstream names, e.g. {@code EDSR_x4.pb} or {@code ESPCN_x3.pb}.
 */
public final class UpscaleModel {

    /** Algorithm name used for the bicubic control. */
    public static final String BICUBIC = "bicubic";

    /** Scales supported by each dnn_superres algorithm. */
    private static final Map<String, Set<Integer>> SUPPORTED_SCALES = Map.of(
            "edsr", Set.of(2, 3, 4),
            "espcn", Set.of(2, 3, 4),
            "fsrcnn", Set.of(2, 3, 4),
            "lapsrn", Set.of(2, 4, 8));

    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "edsr", "EDSR",
            "espcn", "ESPCN",
            "fsrcnn", "FSRCNN",
            "lapsrn", "LapSRN",
            BICUBIC, "Bicubic");

    private static final Pattern FILE_PATTERN = Pattern.compile(
            "^(edsr|espcn|fsrcnn|lapsrn)_x(\\d)\\.pb$", Pattern.CASE_INSENSITIVE);

    private final String algorithm;
    private final int scale;
    private final Path file;

    private UpscaleModel(String algorithm, int scale, Path file) {
        this.algorithm = algorithm;
        this.scale = scale;
        this.file = file;
    }

    /**
     * Create the bicubic control for the given scale.
     * @param scale integer scale factor
     * @return a model that needs no file
     */
    public static UpscaleModel bicubic(int scale) {
        return new UpscaleModel(BICUBIC, scale, null);
    }

    /**
     * Try to interpret a file as a dnn_superres model, using its name.
     * @param path the model file
     * @return the model, or empty if the name is not recognised or the scale is not valid for the algorithm
     */
    public static Optional<UpscaleModel> fromFile(Path path) {
        if (path == null || path.getFileName() == null)
            return Optional.empty();
        Matcher matcher = FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches())
            return Optional.empty();
        String algorithm = matcher.group(1).toLowerCase(Locale.ROOT);
        int scale = Integer.parseInt(matcher.group(2));
        if (!SUPPORTED_SCALES.get(algorithm).contains(scale))
            return Optional.empty();
        return Optional.of(new UpscaleModel(algorithm, scale, path));
    }

    /** @return lower-case algorithm name, as expected by {@code DnnSuperResImpl.setModel} */
    public String getAlgorithm() {
        return algorithm;
    }

    /** @return the integer upscaling factor */
    public int getScale() {
        return scale;
    }

    /** @return the model file, or null for the bicubic control */
    public Path getFile() {
        return file;
    }

    /** @return true if this is the bicubic control (no network involved) */
    public boolean isBicubic() {
        return BICUBIC.equals(algorithm);
    }

    /** @return a short identifier, e.g. {@code edsr_x4}, used to remember the last choice */
    public String getId() {
        return algorithm + "_x" + scale;
    }

    /** @return a human-readable name, e.g. {@code EDSR x4} */
    public String getDisplayName() {
        return DISPLAY_NAMES.get(algorithm) + " x" + scale + (isBicubic() ? " (control)" : "");
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof UpscaleModel))
            return false;
        UpscaleModel other = (UpscaleModel) obj;
        return scale == other.scale && algorithm.equals(other.algorithm) && Objects.equals(file, other.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithm, scale, file);
    }
}
