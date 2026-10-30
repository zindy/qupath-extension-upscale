package qupath.ext.upscale;

import java.util.ArrayList;
import java.util.List;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.opencv_dnn_superres.DnnSuperResImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compute backend for the dnn_superres networks.
 * <p>
 * {@link #CPU} is always available. {@link #OPENCL} and {@link #CUDA} are only offered if the OpenCV build
 * that QuPath is running with reports a usable device. QuPath's default OpenCV build has no CUDA support,
 * so in a stock installation CUDA will not be listed.
 */
public enum Backend {

    /** Plain OpenCV on the CPU. */
    CPU("CPU"),
    /** OpenCV DNN with the OpenCL target (Intel/AMD/NVIDIA GPUs through the vendor's OpenCL driver). */
    OPENCL("GPU (OpenCL)"),
    /** OpenCV DNN with the CUDA backend (needs a CUDA-enabled OpenCV build). */
    CUDA("GPU (CUDA)");

    private static final Logger logger = LoggerFactory.getLogger(Backend.class);

    /** Probed once, because the first OpenCL/CUDA query initialises the runtime. */
    private static volatile List<Backend> available;

    private final String displayName;

    Backend(String displayName) {
        this.displayName = displayName;
    }

    /** @return true if the network runs on a GPU */
    public boolean isGpu() {
        return this != CPU;
    }

    /** @return a human-readable name */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Select the backend and target on a network.
     * Note that OpenCV initialises networks lazily and can fall back to the CPU (with a log warning)
     * for layers the backend does not support, so setting these does not prove the GPU is used.
     * @param sr the network
     */
    void apply(DnnSuperResImpl sr) {
        switch (this) {
            case CPU -> {
                sr.setPreferableBackend(opencv_dnn.DNN_BACKEND_OPENCV);
                sr.setPreferableTarget(opencv_dnn.DNN_TARGET_CPU);
            }
            case OPENCL -> {
                sr.setPreferableBackend(opencv_dnn.DNN_BACKEND_OPENCV);
                sr.setPreferableTarget(opencv_dnn.DNN_TARGET_OPENCL);
            }
            case CUDA -> {
                sr.setPreferableBackend(opencv_dnn.DNN_BACKEND_CUDA);
                sr.setPreferableTarget(opencv_dnn.DNN_TARGET_CUDA);
            }
        }
    }

    /**
     * @return the backends that can be used with the running OpenCV, always starting with {@link #CPU}
     */
    public static List<Backend> available() {
        List<Backend> result = available;
        if (result == null) {
            synchronized (Backend.class) {
                result = available;
                if (result == null) {
                    List<Backend> list = new ArrayList<>();
                    list.add(CPU);
                    if (probeOpenCL())
                        list.add(OPENCL);
                    if (probeCuda())
                        list.add(CUDA);
                    result = List.copyOf(list);
                    available = result;
                    logger.info("Upscale backends available: {}", result);
                }
            }
        }
        return result;
    }

    /**
     * Interpret a stored preference.
     * @param name the enum name, possibly null or stale
     * @return the matching backend if it is available, otherwise {@link #CPU}
     */
    public static Backend resolve(String name) {
        if (name != null) {
            for (Backend backend : available()) {
                if (backend.name().equals(name))
                    return backend;
            }
        }
        return CPU;
    }

    private static boolean probeOpenCL() {
        try {
            return opencv_core.haveOpenCL();
        } catch (Throwable t) {
            logger.debug("OpenCL probe failed", t);
            return false;
        }
    }

    /**
     * This only shows that OpenCV was built with CUDA and sees a device. It does not prove that the DNN module
     * was built with its CUDA backend; if that is missing, OpenCV falls back to the CPU at the first forward pass.
     */
    private static boolean probeCuda() {
        try {
            return opencv_core.getCudaEnabledDeviceCount() > 0;
        } catch (Throwable t) {
            // CPU-only builds throw ("library is compiled without CUDA support")
            logger.debug("CUDA probe failed", t);
            return false;
        }
    }
}
