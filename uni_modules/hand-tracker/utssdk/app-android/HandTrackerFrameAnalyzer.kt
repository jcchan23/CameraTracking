package uts.sdk.modules.handTracker

import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX 分析帧入口。
 *
 * 这一层专门承接 CameraX 的 ImageAnalysis 回调，避免把实验性图像访问
 * 混进 HandTrackerCoordinator 的绑定逻辑里。
 *
 * 设计目标：
 * - 让实验 API 只出现在一个顶层命名位置；
 * - 让 coordinator 继续保持“纯绑定”职责；
 * - 只做帧校验、日志和调度，不在这里展开图像转换细节。
 *
 * 阅读顺序建议：
 * 1. 先看 analyze(...) 的主流程；
 * 2. 再看错误和调试输出；
 * 3. 最后看描述图像格式的辅助函数。
 */
class HandTrackerFrameAnalyzer(
    private val isRunning: () -> Boolean,
    private val getDetector: () -> MediaPipeHandDetector?,
    private val isDetectorReady: () -> Boolean,
    private val onError: ((String) -> Unit)?
) : ImageAnalysis.Analyzer {
    /**
     * CameraX 分析帧入口。
     *
     * 这里负责运行态和格式校验，然后把帧交给 detector，最后统一关闭 imageProxy。
     */
    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (!isRunning()) {
                return
            }

            val detector = getDetector()
            if (detector == null || !isDetectorReady()) {
                return
            }

            if (!isRgbaFormat(imageProxy.format)) {
                val imageDescription = describeImageProxy(imageProxy)
                throw IllegalStateException(
                    "CameraX 分析帧格式错误，期望 RGBA_8888，实际 ${describeImageFormat(imageProxy.format)}；$imageDescription"
                )
            }

            val analyzeStartedAt = SystemClock.uptimeMillis()
            val timestampMs = if (imageProxy.imageInfo.timestamp > 0L) {
                imageProxy.imageInfo.timestamp / 1_000_000L
            } else {
                SystemClock.uptimeMillis()
            }

            val submitted = detector.detect(imageProxy, timestampMs, analyzeStartedAt)
            if (!submitted) {
                return
            }
        } catch (error: Throwable) {
            handleError(normalizeErrorMessage(error, "CameraX 分析帧失败"))
        } finally {
            try {
                imageProxy.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 统一输出错误。
     *
     * 这里只负责记日志和通知上层，不在分析器内做恢复动作。
     */
    private fun handleError(message: String) {
        Log.e(FRAME_ANALYZER_LOG_TAG, message)
        val callback = this.onError
        if (callback != null) {
            callback(message)
        }
    }

    /**
     * 把异常信息收口成稳定文案。
     */
    private fun normalizeErrorMessage(error: Throwable, fallback: String): String {
        val message = error.message
        if (!message.isNullOrEmpty()) {
            return message
        }
        return fallback
    }

    /**
     * 生成可读的 ImageProxy 描述。
     *
     * 这个描述只用于日志，不参与业务判断。
     */
    private fun describeImageProxy(imageProxy: ImageProxy) : String {
        return "format=${describeImageFormat(imageProxy.format)}, " +
            "size=${imageProxy.width}x${imageProxy.height}"
    }

    /**
     * 把图像格式转成可读名字。
     */
    private fun describeImageFormat(format: Int) : String {
        if (isRgbaFormat(format)) {
            return "RGBA_8888"
        }

        return when (format) {
            android.graphics.ImageFormat.YUV_420_888 -> "YUV_420_888"
            android.graphics.ImageFormat.NV21 -> "NV21"
            else -> "UNKNOWN($format)"
        }
    }

    /**
     * 判断是否为允许的 RGBA 输入。
     */
    private fun isRgbaFormat(format: Int) : Boolean {
        return format == ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 ||
            format == PixelFormat.RGBA_8888
    }

    private companion object {
        private const val FRAME_ANALYZER_LOG_TAG: String = "hand-tracker-mp"
    }
}
