/**
 * Example: create a new project entry that holds an upscaled version of the current image,
 * using the image server of the Upscale extension (qupath-extension-upscale).
 *
 * The entry stores a recipe (source image, model, channels, tile settings, channel ranges), not pixels:
 * the upscaled image is recomputed when the entry is opened.
 *
 * Requirements:
 *  - the Upscale extension is installed
 *  - a project is open, and an image from it is shown in the viewer
 *  - the model folder is set (Edit > Preferences > Upscale extension), unless you use a bicubic model id
 */

import qupath.ext.upscale.Backend
import qupath.ext.upscale.ModelLibrary
import qupath.ext.upscale.UpscaleExtension
import qupath.ext.upscale.UpscaleImageServer
import qupath.lib.gui.commands.ProjectCommands
import qupath.lib.gui.scripting.QPEx

// ============================ SETTINGS ============================
// Model identifier: algorithm_xScale, e.g. "edsr_x4", "espcn_x2", "fsrcnn_x3", "lapsrn_x4".
// Use "bicubic_x2" (or x3, x4, x8) for the plain bicubic control.
String MODEL_ID = "espcn_x2"

// Where to run the network: Backend.CPU, Backend.OPENCL or Backend.CUDA (if available on this machine)
Backend BACKEND = Backend.CPU

// Channels to process, by name. null = all channels. Ignored for RGB images.
def CHANNEL_NAMES = null
// def CHANNEL_NAMES = ["CD3", "CD20"]
// ==================================================================

// Get the current project
def project = getProject()
if (project == null) {
    println "No project open!"
    return
}

// The current image
def imageData = getCurrentImageData()
if (imageData == null) {
    println "No image in the current viewer!"
    return
}
def imageType = imageData.getImageType()
def serverOriginal = imageData.getServer()

// Find the model in the model folder (bicubic models need no file)
def models = ModelLibrary.available(UpscaleExtension.modelFolderProperty().get())
def model = ModelLibrary.findById(models, MODEL_ID).orElse(null)
if (model == null) {
    println "Model '" + MODEL_ID + "' not found. Available: " + models.collect { it.getId() }.join(", ")
    println "Set the model folder in Edit > Preferences > Upscale extension."
    return
}

// Channels to process (RGB images are upscaled as a whole, so no channel list is needed)
int[] bands
if (serverOriginal.isRGB()) {
    bands = new int[0]
} else if (CHANNEL_NAMES == null) {
    bands = (0..<serverOriginal.nChannels()) as int[]
} else {
    def channels = serverOriginal.getMetadata().getChannels()
    bands = CHANNEL_NAMES.collect { String name ->
        int i = channels.findIndexOf { it.getName() == name }
        if (i < 0)
            throw new IllegalArgumentException("No channel called " + name)
        i
    }.unique().sort() as int[]
}

// Tile settings come from the extension's preferences
int pad = UpscaleExtension.padProperty().get()
int tile = UpscaleExtension.tileProperty().get()

// The upscaled server. The network is loaded and run once on a dummy image, so a missing file or an
// unusable backend is reported here.
def upscaledServer = new UpscaleImageServer(serverOriginal, model, BACKEND, bands, pad, tile)

// Name of the new entry
def sourceName = getProjectEntry() == null ? serverOriginal.getMetadata().getName() : getProjectEntry().getImageName()
def newEntryName = "${sourceName} - ${model.getDisplayName()}"

// Add the image to the project. This stores the server's builder, which is what lets the entry be rebuilt later.
def entry = ProjectCommands.addSingleImageToProject(project, upscaledServer, imageType)
entry.setImageName(newEntryName)

// Load the image data and its hierarchy (this also rebuilds the server from the stored recipe,
// so an error here means the entry could not be reopened later either)
def upscaledImageData = entry.readImageData()

// Brightfield images: carry the stain vectors over
def stains = imageData.getColorDeconvolutionStains()
if (stains != null)
    upscaledImageData.setColorDeconvolutionStains(stains)

// Write a thumbnail
def img = ProjectCommands.getThumbnailRGB(upscaledServer)
entry.setThumbnail(img)

// Save the modified entry
entry.saveImageData(upscaledImageData)

// Closing the upscaled server (releases a shared GPU network, if any). The source image stays open.
upscaledServer.close()

// Update the project
QPEx.getQuPath().refreshProject()

println "Added '" + newEntryName + "' to the project"

// To open it straight away:
// QPEx.getQuPath().openImageEntry(entry)
