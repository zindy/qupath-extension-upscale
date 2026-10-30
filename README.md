# QuPath Upscale extension

View an image at a higher pixel density in [QuPath](https://qupath.github.io), computed on the fly with
[OpenCV's `dnn_superres`](https://github.com/opencv/opencv_contrib/tree/master/modules/dnn_superres) neural networks
(EDSR, ESPCN, FSRCNN, LapSRN). Plain bicubic interpolation is built in as a control.

The upscaled image is a normal QuPath image: the pixel size is divided by the scale, so objects, measurements and
other extensions (cell detection, segmentation, ...) see the calibrated, larger image. It can also be added to a project.

> **Status: experimental.** The output is for visualisation and for experiments such as running segmentation on
> upscaled images. It is not suitable for quantifying intensities. See [Limitations](#limitations).

## Features

- **Enhance!** Upscale the image in the active viewer with the last-used model, in one click.
- **Multichannel and RGB.** Multichannel images are processed channel by channel (by default only the channels
  currently displayed, which is much faster). RGB images go through the network as a whole.
- **Bicubic control.** Compare any network against plain bicubic interpolation at the same scale, to see what the
  network adds beyond smoothing.
- **CPU or GPU.** Tiles are processed in parallel on the CPU. An OpenCL GPU backend is offered when OpenCV reports a
  usable device.
- **Reset view.** Puts the original image back in the viewer.
- **Add to project.** Stores the upscaled image as a project entry (see [Projects](#projects)).

## Requirements

- QuPath 0.7.0 or later.
- A QuPath build whose OpenCV includes the `dnn_superres` module (it is present in the QuPath build this was
  developed with).
- One or more `dnn_superres` model files (see [Models](#models)). The bicubic control works without any.

## Installation

Download the latest `.jar` from the releases page and drag it onto the QuPath main window. QuPath will offer to create
an extensions folder if you don't have one yet. Restart QuPath afterwards.

### Building from source

```bash
./gradlew build
```

The extension jar is written to `build/libs/`.

## Models

The networks are not bundled. Download the `.pb` files from the links in the
[dnn_superres README](https://github.com/opencv/opencv_contrib/tree/master/modules/dnn_superres) and put them in one
folder. Keep the original file names; the extension reads the algorithm and scale from them:

| Algorithm | Scales | Example file |
|---|---|---|
| EDSR | 2, 3, 4 | `EDSR_x4.pb` |
| ESPCN | 2, 3, 4 | `ESPCN_x3.pb` |
| FSRCNN | 2, 3, 4 | `FSRCNN_x2.pb` |
| LapSRN | 2, 4, 8 | `LapSRN_x8.pb` |

Bicubic is always available at scales 2, 3, 4 and 8. The model weights come with their own licences; check them
before redistributing.

## Usage

Menu: **Extensions > Upscale**

| Item | What it does |
|---|---|
| Enhance! | Upscale the active viewer with the model and channel choice from last time |
| Enhance... | Open the options dialog |
| Add to project | Add the upscaled image in the active viewer to the open project |
| Reset view | Restore the original image in the active viewer |

The first time, set the model folder: either in **Edit > Preferences > Upscale extension**, or in the dialog with
**Browse...**, which accepts any model file and uses its folder, so the other models next to it are found too.

The dialog has:

- **Model**: the models found in the folder, plus the bicubic controls
- **Backend**: only shown if a GPU backend is available
- **Displayed channels only**: process just the channels currently displayed (ignored for RGB images)

### Preferences

| Preference | Default | Meaning |
|---|---|---|
| Model folder | (empty) | Folder containing the model files |
| Tile overlap (source pixels) | 24 | Extra source pixels read around each tile to avoid seams. Larger networks (EDSR) need more |
| Tile size (output pixels) | 960 | Preferred size of upscaled tiles; adjusted to a multiple of the scale |
| OpenCV threads (0 = unchanged) | 0 | Sets OpenCV's thread count when an upscaled view is created. This is global to QuPath |

### Backends

- **CPU** is always available. Each thread gets its own copy of the network, so several tiles are processed at once.
  OpenCV also parallelises internally, so it can be worth trying a low *OpenCV threads* value.
- **GPU (OpenCL)** is listed when OpenCV reports an OpenCL device. A single network instance is shared and calls are
  serialised. The first tile is slower while OpenCL compiles its kernels. For small networks the GPU is not
  necessarily faster than the CPU, so measure. OpenCV can silently fall back to the CPU for layers a backend does not
  support, so use your GPU monitor to confirm that the GPU is actually busy.
- **GPU (CUDA)** is only listed if the OpenCV build has CUDA support. QuPath's default build does not.

## How it works

- The upscaled image has `scale` times the width and height, and a pixel size divided by `scale`.
- Full-resolution tiles are upscaled; zoomed-out levels (downsample of at least `scale`) are read straight from the
  source, since upscaling them would add nothing.
- Each tile is read with a margin of source pixels (*tile overlap*), upscaled, and cropped, to avoid visible seams.
- The networks only accept 8-bit, 3-channel input. For multichannel images each channel is mapped to 8 bits using a
  fixed range (from the minimum, up to 1.25 times the 99.9th percentile of the non-zero pixels), replicated to three
  channels, upscaled, and mapped back to the original pixel type.
- Tiles are cached by QuPath, so each tile is upscaled only once per view.

## Projects

**Add to project** creates a project entry that stores a *recipe*, not pixels: the source image, the model, the
channels, the tile settings and the channel ranges. Objects drawn on the upscaled view are saved with the entry.

When the entry is opened, the image is rebuilt from that recipe, so:

- the extension must be installed on the machine that opens the project,
- the model must be in that machine's model folder (it is looked up by name, e.g. `edsr_x4`, not by path),
- the backend is not stored: the one last chosen on that machine is used, falling back to the CPU.

The image is recomputed as you view it; nothing is written to disk. If you need a file (for example to share, or to
run something that expects a conventional image), export the upscaled image with QuPath's usual export tools.

## Scripting

The image server can be used directly from a Groovy script. It wraps any QuPath image server:

```groovy
import qupath.ext.upscale.*

def model = ModelLibrary.findById(
        ModelLibrary.available(UpscaleExtension.modelFolderProperty().get()), "espcn_x2").get()
def server = new UpscaleImageServer(getCurrentServer(), model, Backend.CPU, [0, 1, 2] as int[], 24, 960)
```

[`examples/create_upscaled_project_entry.groovy`](examples/create_upscaled_project_entry.groovy) shows the complete
workflow: pick a model and channels, create the server, add it to the open project as a new entry, and save it with a
thumbnail.

## Limitations

- **Not for quantification.** The 8-bit quantisation limits the output to 256 intensity levels per channel, and
  values outside the mapping range are clipped.
- **The detail is invented.** These networks were trained on natural images. They produce plausible structure, not
  measured structure. This is especially relevant for data whose resolution is physically limited (for example imaging
  mass cytometry, where the acquisition limits the information to about the pixel size). Compare against the bicubic
  control and treat apparent new structure with suspicion, and validate any downstream result (such as segmentation)
  yourself.
- **Speed.** EDSR is several orders of magnitude slower than ESPCN or FSRCNN on a CPU. Processing only the displayed
  channels is the biggest saving on multichannel images.
- **Annotations** drawn on an upscaled view are in the coordinates of the upscaled image (original coordinates times
  the scale). They belong to the upscaled view and are lost on *Reset view* unless the view was added to a project.
- **Brightfield and other RGB images** are upscaled as colour images; ESPCN, FSRCNN and LapSRN work on luminance and
  interpolate the colour.

## Acknowledgements

- [QuPath](https://qupath.github.io): Bankhead, P. et al. *QuPath: Open source software for digital pathology image
  analysis.* Scientific Reports 7, 16878 (2017).
- [OpenCV](https://opencv.org) `dnn_superres`, and the original authors of the networks: EDSR (Lim et al., 2017),
  ESPCN (Shi et al., 2016), FSRCNN (Dong et al., 2016) and LapSRN (Lai et al., 2017).
- Built from the [QuPath extension template](https://github.com/qupath/qupath-extension-template).

## License

[MIT](LICENSE)