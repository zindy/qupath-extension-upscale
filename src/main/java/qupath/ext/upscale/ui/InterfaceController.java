package qupath.ext.upscale.ui;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ResourceBundle;

import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import qupath.ext.upscale.Backend;
import qupath.ext.upscale.ModelLibrary;
import qupath.ext.upscale.UpscaleExtension;
import qupath.ext.upscale.UpscaleManager;
import qupath.ext.upscale.UpscaleModel;
import qupath.fx.dialogs.Dialogs;

/**
 * Controller for the options dialog contained in interface.fxml
 * ("Extensions > Upscale > Upscale...").
 */
public class InterfaceController extends VBox {

    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.upscale.ui.strings");

    @FXML
    private ComboBox<UpscaleModel> modelCombo;

    @FXML
    private Label folderLabel;

    @FXML
    private VBox backendBox;

    @FXML
    private ComboBox<Backend> backendCombo;

    @FXML
    private CheckBox displayedOnlyCheck;

    private final UpscaleManager manager;

    /**
     * Create a new instance of the interface controller.
     * @param manager the manager that applies and removes the upscaled view
     * @return a new instance of the interface controller
     * @throws IOException If reading the extension FXML files fails.
     */
    public static InterfaceController createInstance(UpscaleManager manager) throws IOException {
        return new InterfaceController(manager);
    }

    private InterfaceController(UpscaleManager manager) throws IOException {
        this.manager = manager;
        var url = InterfaceController.class.getResource("interface.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();

        // The checkbox and the folder label follow the persistent preferences
        displayedOnlyCheck.selectedProperty().bindBidirectional(UpscaleExtension.displayedOnlyProperty());
        folderLabel.textProperty().bind(Bindings.createStringBinding(
                this::folderText, UpscaleExtension.modelFolderProperty()));

        refreshModels();
        setupBackends();
    }

    /** Offer the backend choice only if there is a choice: with the CPU alone, the controls stay hidden. */
    private void setupBackends() {
        List<Backend> backends = Backend.available();
        backendCombo.getItems().setAll(backends);
        backendCombo.setValue(Backend.resolve(UpscaleExtension.backendProperty().get()));
        boolean choice = backends.size() > 1;
        backendBox.setVisible(choice);
        backendBox.setManaged(choice);
    }

    private String folderText() {
        String folder = UpscaleExtension.modelFolderProperty().get();
        if (folder == null || folder.isBlank())
            return resources.getString("dialog.folder.none");
        return String.format(resources.getString("dialog.folder"), folder);
    }

    /** Rescan the model folder and refill the dropdown, keeping the last choice if it is still available. */
    @FXML
    private void refreshModels() {
        List<UpscaleModel> models = ModelLibrary.available(UpscaleExtension.modelFolderProperty().get());
        modelCombo.getItems().setAll(models);
        ModelLibrary.findById(models, UpscaleExtension.lastModelProperty().get())
                .ifPresentOrElse(modelCombo::setValue, () -> modelCombo.getSelectionModel().selectFirst());
    }

    /**
     * Pick any model file: the model folder is inferred from it, so the other models next to it are found too.
     */
    @FXML
    private void browseForModel() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(resources.getString("dialog.browse.title"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(resources.getString("dialog.browse.filter"), "*.pb"));
        String folder = UpscaleExtension.modelFolderProperty().get();
        if (folder != null && !folder.isBlank()) {
            File dir = new File(folder);
            if (dir.isDirectory())
                chooser.setInitialDirectory(dir);
        }
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null)
            return;

        ModelLibrary.folderFromFile(file.toPath())
                .ifPresent(p -> UpscaleExtension.modelFolderProperty().set(p.toString()));
        refreshModels();
        UpscaleModel.fromFile(file.toPath())
                .flatMap(m -> ModelLibrary.findById(modelCombo.getItems(), m.getId()))
                .ifPresent(modelCombo::setValue);
    }

    @FXML
    private void enhance() {
        UpscaleModel model = modelCombo.getValue();
        if (model == null) {
            Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.no-model-selected"));
            return;
        }
        UpscaleExtension.lastModelProperty().set(model.getId());
        Backend backend = backendCombo.getValue() == null ? Backend.CPU : backendCombo.getValue();
        UpscaleExtension.backendProperty().set(backend.name());
        manager.enhance(model, backend, displayedOnlyCheck.isSelected());
    }

    @FXML
    private void reset() {
        manager.reset();
    }
}
