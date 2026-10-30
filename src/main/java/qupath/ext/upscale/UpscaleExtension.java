package qupath.ext.upscale;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;
import qupath.ext.upscale.ui.InterfaceController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.io.GsonTools;

/**
 * Upscale: view a multichannel image at a higher pixel density, computed on the fly
 * with OpenCV dnn_superres models (or bicubic interpolation as a control).
 * <p>
 * Menu: Extensions &gt; Upscale &gt; Enhance! / Enhance... / Reset view.
 * <p>
 * <b>Important!</b> The name &amp; package of this class must match the file
 * <pre>
 *     /resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 * </pre>
 */
public class UpscaleExtension implements QuPathExtension {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.upscale.ui.strings");
    private static final Logger logger = LoggerFactory.getLogger(UpscaleExtension.class);

    private static final String EXTENSION_NAME = resources.getString("name");
    private static final String EXTENSION_DESCRIPTION = resources.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

    /** Folder containing the dnn_superres model files (EDSR_x4.pb, ESPCN_x3.pb, ...). Shown in the preferences. */
    private static final StringProperty modelFolder = PathPrefs.createPersistentPreference(
            "upscale.modelFolder", "");

    /** Overlap, in source pixels, added around each tile to avoid seams. Shown in the preferences. */
    private static final IntegerProperty pad = PathPrefs.createPersistentPreference(
            "upscale.pad", 24);

    /** Preferred output tile size. Shown in the preferences. */
    private static final IntegerProperty tile = PathPrefs.createPersistentPreference(
            "upscale.tile", 960);

    /** OpenCV thread count (0 = leave OpenCV as it is). Global to the JVM. Shown in the preferences. */
    private static final IntegerProperty threads = PathPrefs.createPersistentPreference(
            "upscale.cvThreads", 0);

    /** Identifier of the model used last (managed by the dialog, not shown in the preferences). */
    private static final StringProperty lastModel = PathPrefs.createPersistentPreference(
            "upscale.lastModel", "");

    /** Name of the {@link Backend} used last (managed by the dialog, not shown in the preferences). */
    private static final StringProperty backend = PathPrefs.createPersistentPreference(
            "upscale.backend", Backend.CPU.name());

    /** Whether to process only the displayed channels (managed by the dialog). */
    private static final BooleanProperty displayedOnly = PathPrefs.createPersistentPreference(
            "upscale.displayedOnly", true);

    /** @return the model folder preference */
    public static StringProperty modelFolderProperty() {
        return modelFolder;
    }

    /** @return the tile overlap preference */
    public static IntegerProperty padProperty() {
        return pad;
    }

    /** @return the tile size preference */
    public static IntegerProperty tileProperty() {
        return tile;
    }

    /** @return the OpenCV thread count preference */
    public static IntegerProperty threadsProperty() {
        return threads;
    }

    /** @return the identifier of the model used last */
    public static StringProperty lastModelProperty() {
        return lastModel;
    }

    /** @return the name of the backend used last */
    public static StringProperty backendProperty() {
        return backend;
    }

    /** @return the "displayed channels only" preference */
    public static BooleanProperty displayedOnlyProperty() {
        return displayedOnly;
    }

    private boolean isInstalled = false;
    private UpscaleManager manager;
    private Stage stage;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("{} is already installed", getName());
            return;
        }
        isInstalled = true;
        manager = new UpscaleManager(qupath);
        registerServerBuilder();
        addPreferencesToPane(qupath);
        addMenuItems(qupath);
    }

    /**
     * Make project entries that contain an upscaled image readable and writable.
     * QuPath stores a server builder as JSON with a type label; this adds our builder to the known labels.
     * It has to happen before any project is read, which is why it is done when the extension is installed.
     */
    private void registerServerBuilder() {
        try {
            var factory = ImageServers.getServerBuilderFactory();
            if (factory instanceof GsonTools.SubTypeAdapterFactory) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                GsonTools.SubTypeAdapterFactory<ImageServerBuilder.ServerBuilder> subtypes =
                        (GsonTools.SubTypeAdapterFactory<ImageServerBuilder.ServerBuilder>) factory;
                subtypes.registerSubtype(UpscaleServerBuilder.class, "upscale");
                logger.info("Registered the upscale server builder");
            } else {
                logger.warn("Unable to register the upscale server builder: unexpected factory {}",
                        factory == null ? null : factory.getClass().getName());
            }
        } catch (Throwable t) {
            logger.warn("Unable to register the upscale server builder", t);
        }
    }

    private void addPreferencesToPane(QuPathGUI qupath) {
        var items = qupath.getPreferencePane().getPropertySheet().getItems();
        String category = resources.getString("pref.category");

        items.add(new PropertyItemBuilder<>(modelFolder, String.class)
                .name(resources.getString("pref.folder.name"))
                .category(category)
                .description(resources.getString("pref.folder.description"))
                .build());
        items.add(new PropertyItemBuilder<>(pad, Integer.class)
                .name(resources.getString("pref.pad.name"))
                .category(category)
                .description(resources.getString("pref.pad.description"))
                .build());
        items.add(new PropertyItemBuilder<>(tile, Integer.class)
                .name(resources.getString("pref.tile.name"))
                .category(category)
                .description(resources.getString("pref.tile.description"))
                .build());
        items.add(new PropertyItemBuilder<>(threads, Integer.class)
                .name(resources.getString("pref.threads.name"))
                .category(category)
                .description(resources.getString("pref.threads.description"))
                .build());
    }

    private void addMenuItems(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem enhance = new MenuItem(resources.getString("menu.enhance"));
        enhance.setOnAction(e -> enhanceWithLastSettings());

        MenuItem options = new MenuItem(resources.getString("menu.enhance.options"));
        options.setOnAction(e -> showDialog());

        MenuItem addToProject = new MenuItem(resources.getString("menu.add-to-project"));
        addToProject.setOnAction(e -> manager.addToProject());

        MenuItem reset = new MenuItem(resources.getString("menu.reset"));
        reset.setOnAction(e -> manager.reset());

        // Nothing to enhance or reset without an image
        enhance.disableProperty().bind(qupath.imageDataProperty().isNull());
        options.disableProperty().bind(qupath.imageDataProperty().isNull());
        addToProject.disableProperty().bind(qupath.imageDataProperty().isNull());
        reset.disableProperty().bind(qupath.imageDataProperty().isNull());

        menu.getItems().addAll(enhance, options, new SeparatorMenuItem(), addToProject, reset);
    }

    /**
     * "Enhance!": use the model and channel choice from last time.
     * If there is no usable choice yet, explain what is missing or open the options dialog.
     */
    private void enhanceWithLastSettings() {
        String folder = modelFolder.get();
        List<UpscaleModel> models = ModelLibrary.available(folder);
        Optional<UpscaleModel> model = ModelLibrary.findById(models, lastModel.get());
        if (model.isPresent()) {
            manager.enhance(model.get(), Backend.resolve(backend.get()), displayedOnly.get());
            return;
        }
        if (folder == null || folder.isBlank()) {
            Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.no-model-folder"));
            return;
        }
        showDialog();
    }

    private void showDialog() {
        if (stage == null) {
            try {
                stage = new Stage();
                Scene scene = new Scene(InterfaceController.createInstance(manager));
                stage.initOwner(QuPathGUI.getInstance().getStage());
                stage.setTitle(resources.getString("stage.title"));
                stage.setScene(scene);
                stage.setResizable(false);
            } catch (IOException e) {
                stage = null;
                Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.gui-loading-failed"));
                logger.error("Unable to load extension interface FXML", e);
                return;
            }
        }
        stage.show();
        stage.toFront();
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
}
