package uts.sdk.modules.handTracker

import android.content.Context
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import android.util.Size
import kotlin.math.max

typealias PreviewReadyCallback = (width: Int, height: Int) -> Unit
typealias ErrorCallback = (message: String) -> Unit
typealias DetectionCallback = (hands: List<NativeDetectedHand>, width: Int, height: Int, timestampMs: Long) -> Unit

/**
 * CameraX + MediaPipe 的原生协调器。
 *
 * 这一层只做 CameraX 绑定、分析帧分发和坐标映射，不承载页面状态。
 * 页面、service 和 UTS 侧只需要把预览宿主和回调传进来即可。
 *
 * 阅读顺序建议：
 * 1. 先看公开入口；
 * 2. 再看 CameraX 绑定链路；
 * 3. 然后看结果分发和坐标映射；
 * 4. 最后看预览就绪和调试辅助。
 */
class HandTrackerCoordinator(
    private val appId: String
) {
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    @Volatile private var previewView: PreviewView? = null
    @Volatile private var debugCallback: ((String) -> Unit)? = null
    @Volatile private var onPreviewReadyCallback: PreviewReadyCallback? = null
    @Volatile private var onDetectionCallback: DetectionCallback? = null
    @Volatile private var onErrorCallback: ErrorCallback? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var lifecycleOwner: LifecycleOwner? = null
    @Volatile private var detector: MediaPipeHandDetector? = null
    private var analysisExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var running: Boolean = false
    @Volatile private var cameraBound: Boolean = false
    @Volatile private var detectorReady: Boolean = false
    @Volatile private var detectorInitRequested: Boolean = false
    @Volatile private var cameraPermissionGranted: Boolean = false
    @Volatile private var previewReadyReported: Boolean = false
    @Volatile private var layoutListenerInstalled: Boolean = false
    private var frameAnalyzer: HandTrackerFrameAnalyzer? = null
    private val previewStreamObserver = Observer<PreviewView.StreamState> { state ->
        if (state == PreviewView.StreamState.STREAMING) {
            emitPreviewReadyIfPossible()
        }
    }
    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (running) {
            tryBindCamera()
        }
    }

    /**
     * 保存调试回调。
     *
     * 它只负责日志通道，不改变协调器自身状态。
     */
    fun setDebugCallback(callback: ((String) -> Unit)?) {
        this.debugCallback = callback
    }

    /**
     * 绑定预览 View。
     *
     * 绑定新 View 前处理旧 View，避免页面切换时留下重复监听器。
     */
    fun bindPreviewView(previewView: PreviewView) {
        val previousPreviewView = this.previewView
        if (previousPreviewView != null && previousPreviewView !== previewView) {
            detachPreviewView(previousPreviewView)
        }

        this.previewView = previewView
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE)
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER)
        installPreviewViewHooks(previewView)
        tryBindCamera()
    }

    /**
     * 启动 CameraX + MediaPipe 链路。
     *
     * 这里只有状态置位和依赖准备，不直接做相机绑定细节。
     */
    fun startTracking(
        onPreviewReady: PreviewReadyCallback,
        onDetection: DetectionCallback,
        onError: ErrorCallback
    ) {
        this.onPreviewReadyCallback = onPreviewReady
        this.onDetectionCallback = onDetection
        this.onErrorCallback = onError
        this.running = true
        this.previewReadyReported = false
        this.cameraBound = false
        this.detectorReady = false
        this.detectorInitRequested = false
        this.cameraPermissionGranted = false
        this.analysisExecutor?.shutdownNow()
        this.analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        ensureFrameAnalyzer()
        ensureDetector()
        ensureCameraProvider()
        tryBindCamera()
    }

    /**
     * 更新相机权限状态。
     *
     * detector 和 provider 可以提前并行预热，但最终 bind 前必须等权限就绪。
     */
    fun setCameraPermissionGranted(granted: Boolean) {
        this.cameraPermissionGranted = granted
        if (granted) {
            tryBindCamera()
        }
    }

    /**
     * 停止当前协调链路。
     *
     * 这里同时清理 CameraX、detector、分析器和宿主监听器。
     */
    fun stop() {
        this.running = false
        this.previewReadyReported = false
        this.cameraBound = false
        this.detectorReady = false
        this.detectorInitRequested = false
        this.cameraPermissionGranted = false
        this.onPreviewReadyCallback = null
        this.onDetectionCallback = null
        this.onErrorCallback = null

        val provider = this.cameraProvider
        if (provider != null) {
            provider.unbindAll()
        }

        val previewView = this.previewView
        if (previewView != null) {
            previewView.getPreviewStreamState().removeObserver(previewStreamObserver)
            if (layoutListenerInstalled) {
                previewView.removeOnLayoutChangeListener(layoutChangeListener)
            }
        }
        this.layoutListenerInstalled = false

        this.analysisExecutor?.shutdownNow()
        this.analysisExecutor = null

        this.detector?.close()
        this.detector = null
        this.frameAnalyzer = null

        this.cameraProvider = null
        this.lifecycleOwner = null
        this.previewView = null
    }

    /**
     * 初始化或重新绑定 detector。
     *
     * 如果 detector 已存在，就直接补回回调；如果不存在，就创建并异步初始化。
     */
    private fun ensureDetector() {
        if (this.detector != null && this.detectorInitRequested) {
            return
        }

        if (this.detector != null && !this.detectorInitRequested) {
            this.detector!!.bindCallbacks(
                { hands, transform, timestampMs ->
                    dispatchDetection(hands, transform, timestampMs)
                },
                { message ->
                    handleError(message, true)
                }
            )
            return
        }

        val activity = try {
            requireActivity()
        } catch (error: Throwable) {
            handleError(normalizeErrorMessage(error, "无法获取当前 Activity"), true)
            return
        }
        val context = activity as Context
        this.detectorInitRequested = true
        this.detector = MediaPipeHandDetector(context, appId)
        this.detector?.bindCallbacks(
            { hands, transform, timestampMs ->
                dispatchDetection(hands, transform, timestampMs)
            },
            { message ->
                handleError(message, true)
            }
        )
        this.detector?.ensureReadyAsync(
            8000L,
            onReady = {
                this.detectorReady = true
                tryBindCamera()
            },
            onError = { message ->
                handleError(message, true)
            }
        )
    }

    /**
     * 初始化分析器。
     *
     * 这一层只把运行状态、detector 和日志通道串起来，不处理图像转换。
     */
    private fun ensureFrameAnalyzer() {
        if (this.frameAnalyzer != null) {
            return
        }

        this.frameAnalyzer = HandTrackerFrameAnalyzer(
            isRunning = { this.running },
            getDetector = { this.detector },
            isDetectorReady = { this.detectorReady },
            onError = { message ->
                handleError(message, true)
            }
        )
    }

    /**
     * 获取 ProcessCameraProvider。
     *
     * 这里单独拆出来，是因为它本身带有异步就绪和 Activity 生命周期要求。
     */
    private fun ensureCameraProvider() {
        if (this.cameraProvider != null) {
            return
        }

        val activity = try {
            requireActivity()
        } catch (error: Throwable) {
            handleError(normalizeErrorMessage(error, "无法获取当前 Activity"), true)
            return
        }
        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner == null) {
            handleError("当前 Activity 不是 LifecycleOwner，无法绑定 CameraX", true)
            return
        }

        this.lifecycleOwner = lifecycleOwner
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener(
            {
                if (!this.running) {
                    return@addListener
                }

                try {
            this.cameraProvider = future.get()
                    tryBindCamera()
                } catch (error: Throwable) {
                    handleError(normalizeErrorMessage(error, "获取 ProcessCameraProvider 失败"), true)
                }
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    /**
     * 尝试把 Preview、ImageAnalysis 和生命周期绑定到一起。
     *
     * 这个方法会反复被布局变化和 Provider 就绪回调触发，所以内部只做条件检查和一次性绑定。
     */
    private fun tryBindCamera() {
        if (!this.running || this.cameraBound) {
            return
        }

        // detector 和 provider 可以提前就绪，但真正 bind 之前必须先拿到相机权限。
        if (!this.cameraPermissionGranted) {
            return
        }

        val previewView = this.previewView ?: return
        val provider = this.cameraProvider ?: return
        val lifecycleOwner = this.lifecycleOwner ?: return

        if (previewView.width <= 0 || previewView.height <= 0) {
            if (!layoutListenerInstalled) {
                previewView.addOnLayoutChangeListener(layoutChangeListener)
                layoutListenerInstalled = true
            }
            return
        }

        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val viewPort: ViewPort = previewView.getViewPort() ?: run {
            if (!layoutListenerInstalled) {
                previewView.addOnLayoutChangeListener(layoutChangeListener)
                layoutListenerInstalled = true
            }
            return
        }

        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()

        preview.setSurfaceProvider(previewView.getSurfaceProvider())

        try {
            val analysisExecutor = this.analysisExecutor ?: run {
                this.analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                this.analysisExecutor!!
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = this.frameAnalyzer ?: run {
                ensureFrameAnalyzer()
                this.frameAnalyzer ?: throw IllegalStateException("CameraX 分析器尚未初始化")
            }
            imageAnalysis.setAnalyzer(analysisExecutor, analyzer)
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(imageAnalysis)
                    .build()
            )
            this.cameraBound = true
            emitPreviewReadyIfPossible()
        } catch (error: Throwable) {
            handleError(normalizeErrorMessage(error, "CameraX 绑定失败"), true)
            return
        }

        previewView.getPreviewStreamState().removeObserver(previewStreamObserver)
        previewView.getPreviewStreamState().observe(lifecycleOwner, previewStreamObserver)
    }

    /**
     * 把原生检测结果分发给页面层。
     *
     * 这里根据当前运行态和预览尺寸做坐标映射，然后回调给上层。
     */
    private fun dispatchDetection(hands: List<NativeDetectedHand>, transform: FrameDisplayTransform, timestampMs: Long) {
        if (!this.running) {
            return
        }

        val onDetection = this.onDetectionCallback ?: return
        val previewView = this.previewView ?: return
        if (previewView.width <= 0 || previewView.height <= 0) {
            return
        }

        val mappedHands = mapHandsToPreview(hands, transform, previewView.width, previewView.height)
        val dispatchWidth = max(previewView.width, 1)
        val dispatchHeight = max(previewView.height, 1)
        if (!this.running) {
            return
        }

        onDetection(mappedHands, dispatchWidth, dispatchHeight, timestampMs)
    }

    /**
     * 将分析坐标映射到预览坐标。
     *
     * 这里只做一次旋转和一次镜像修正，避免页面侧再重复做同样的几何变换。
     */
    private fun mapHandsToPreview(
        hands: List<NativeDetectedHand>,
        transform: FrameDisplayTransform,
        previewWidth: Int,
        previewHeight: Int
    ): List<NativeDetectedHand> {
        if (hands.isEmpty()) {
            return hands
        }

        var hasVisibleHand = false
        for (hand in hands) {
            if (hand.visible) {
                hasVisibleHand = true
                break
            }
        }

        if (!hasVisibleHand) {
            return hands
        }

        val analysisWidth = transform.analysisWidth.toDouble()
        val analysisHeight = transform.analysisHeight.toDouble()
        val previewWidthDouble = previewWidth.toDouble()
        val previewHeightDouble = previewHeight.toDouble()
        val mappedHands = ArrayList<NativeDetectedHand>(2)

        for (hand in hands) {
            if (!hand.visible) {
                mappedHands.add(hand)
            } else {
                val mappedCenterX = previewWidthDouble * (1.0 - (hand.centerY / analysisHeight))
                val mappedCenterY = previewHeightDouble * (1.0 - (hand.centerX / analysisWidth))
                mappedHands.add(
                    hand.copy(
                        centerX = mappedCenterX,
                        centerY = mappedCenterY,
                        size = max(hand.size, 1.0)
                    )
                )
            }
        }

        return mappedHands
    }

    /**
     * 在预览真正可用时，向上层补发一次 ready 回调。
     *
     * 这样页面可以尽早拿到可用尺寸，而不必等第一帧检测结果。
     */
    private fun emitPreviewReadyIfPossible() {
        if (this.previewReadyReported || !this.cameraBound || !this.running) {
            return
        }

        val previewView = this.previewView ?: return
        if (previewView.width <= 0 || previewView.height <= 0) {
            return
        }

        this.previewReadyReported = true
        val callback = this.onPreviewReadyCallback ?: return
        mainHandler.post {
            if (!this.running) {
                return@post
            }

            callback(previewView.width, previewView.height)
        }
    }

    /**
     * 安装预览 View 的监听器。
     *
     * 这里同时监听布局和流状态，以便在宿主真正可用时再绑定相机。
     */
    private fun installPreviewViewHooks(previewView: PreviewView) {
        previewView.getPreviewStreamState().removeObserver(previewStreamObserver)
        if (this.lifecycleOwner != null) {
            previewView.getPreviewStreamState().observe(this.lifecycleOwner!!, previewStreamObserver)
        }

        if (!layoutListenerInstalled) {
            previewView.addOnLayoutChangeListener(layoutChangeListener)
            layoutListenerInstalled = true
        }
    }

    /**
     * 解除旧预览 View 的监听器。
     *
     * 页面替换宿主时先做这一步，避免旧 View 继续持有协调器回调。
     */
    private fun detachPreviewView(previewView: PreviewView) {
        previewView.getPreviewStreamState().removeObserver(previewStreamObserver)
        if (layoutListenerInstalled) {
            previewView.removeOnLayoutChangeListener(layoutChangeListener)
        }
        layoutListenerInstalled = false
    }

    /**
     * 获取当前 Activity。
     *
     * 这个入口只负责把 UTS 环境里的 Activity 取出来，不在这里做额外恢复。
     */
    private fun requireActivity() : android.app.Activity {
        val activity = io.dcloud.uts.UTSAndroid.getUniActivity()
            ?: throw IllegalStateException("无法获取当前 Activity")
        return activity
    }

    /**
     * 把异常信息转成可读文案。
     */
    private fun normalizeErrorMessage(error: Throwable, fallback: String): String {
        val message = error.message
        if (!message.isNullOrEmpty()) {
            return message
        }

        return fallback
    }

    /**
     * 统一处理协调器错误。
     *
     * stopSession 表示是否需要直接停止整条链路。
     */
    private fun handleError(message: String, stopSession: Boolean) {
        val callback = this.onErrorCallback
        if (callback != null) {
            mainHandler.post {
                callback(message)
            }
        }

        if (stopSession) {
            stop()
        }
    }

}

private const val COORDINATE_LOG_TAG: String = "hand-tracker-mp"
private const val ANALYSIS_TO_PREVIEW_ROTATION_DEGREES: Float = 270f
