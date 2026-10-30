package qupath.ext.upscale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the models that can be used: every recognised dnn_superres file in the model folder,
 * plus the bicubic controls, which need no file.
 */
public final class ModelLibrary {

    private static final Logger logger = LoggerFactory.getLogger(ModelLibrary.class);

    private static final List<String> ALGORITHM_ORDER = List.of("edsr", "espcn", "fsrcnn", "lapsrn");

    private static final int[] BICUBIC_SCALES = {2, 3, 4, 8};

    private ModelLibrary() {
        // static helpers only
    }

    /**
     * Scan a folder (non-recursively) for model files such as {@code EDSR_x4.pb}.
     * @param folder path of the folder; may be null or blank
     * @return the recognised models, sorted by algorithm then scale; empty if the folder is not usable
     */
    public static List<UpscaleModel> scan(String folder) {
        List<UpscaleModel> models = new ArrayList<>();
        if (folder == null || folder.isBlank())
            return models;
        Path dir = Paths.get(folder);
        if (!Files.isDirectory(dir))
            return models;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .forEach(p -> UpscaleModel.fromFile(p).ifPresent(models::add));
        } catch (IOException e) {
            logger.warn("Unable to list model folder {}: {}", folder, e.getMessage());
        }
        models.sort(Comparator
                .comparingInt((UpscaleModel m) -> ALGORITHM_ORDER.indexOf(m.getAlgorithm()))
                .thenComparingInt(UpscaleModel::getScale));
        return models;
    }

    /**
     * Everything that can be selected: the models found in the folder, followed by the bicubic controls.
     * @param folder path of the model folder; may be null or blank
     * @return the available models
     */
    public static List<UpscaleModel> available(String folder) {
        List<UpscaleModel> models = scan(folder);
        for (int scale : BICUBIC_SCALES)
            models.add(UpscaleModel.bicubic(scale));
        return models;
    }

    /**
     * Find a model by its identifier (see {@link UpscaleModel#getId()}).
     * @param models the candidates
     * @param id the identifier to look for; may be null
     * @return the matching model, if any
     */
    public static Optional<UpscaleModel> findById(List<UpscaleModel> models, String id) {
        if (id == null || id.isBlank())
            return Optional.empty();
        return models.stream().filter(m -> id.equals(m.getId())).findFirst();
    }

    /**
     * Infer the model folder from one model file: all the other models are expected next to it.
     * @param file any model file
     * @return the parent folder, if there is one
     */
    public static Optional<Path> folderFromFile(Path file) {
        if (file == null)
            return Optional.empty();
        return Optional.ofNullable(file.toAbsolutePath().getParent());
    }
}
