package uts.sdk.modules.handTracker

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.Arrays
import kotlin.collections.List

/**
 * Android 原生侧输出给 UTS 的手部结果。
 *
 * visible 为 false 时表示当前槽位无有效检测结果，
 * 这样 UTS 层可以稳定输出 left/right 两个固定槽位，而不是自己再做空结果推断。
 */
data class NativeDetectedHand(
    val label: String = "",
    val visible: Boolean = false,
    val confidence: Double = 0.0,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val size: Double = 0.0
)

internal data class HandCentroid(
    val x: Double,
    val y: Double,
    val size: Double
)

data class FrameDisplayTransform(
    val analysisWidth: Int,
    val analysisHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int
)

/**
 * MediaPipe 手部检测封装。
 *
 * 这一层只负责两件事：
 * 1. 初始化 HandLandmarker；
 * 2. 把输入帧转换成左右手质心结果。
 *
 * CameraX 预览、权限、宿主生命周期都不在这里处理。
 */
class MediaPipeHandDetector(
    private val appContext: Context,
    private val appId: String
) {
    private val initLock = Any()
    private val detectionLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initExecutor = Executors.newSingleThreadExecutor()
    private val timeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var landmarker: HandLandmarker? = null
    private var initializationRunning: Boolean = false
    private var initGeneration: Int = 0
    private var pendingInitCallbacks: MutableList<PendingInitCallback> = mutableListOf()
    @Volatile private var detectionInFlight: Boolean = false
    @Volatile private var pendingFrameTiming: PendingFrameTiming? = null
    @Volatile private var pendingFrameTimeoutFuture: ScheduledFuture<*>? = null
    @Volatile private var onDetectionCallback: ((List<NativeDetectedHand>, FrameDisplayTransform, Long) -> Unit)? = null
    @Volatile private var onErrorCallback: ((String) -> Unit)? = null
    private val analyzeToSubmitBenchmark = LatencyWindow("analyze->submit", MEDIAPIPE_LOG_TAG)
    private val submitToResultBenchmark = LatencyWindow("submit->result", MEDIAPIPE_LOG_TAG)
    private val modelAssetPath: String = buildModelAssetPath(appId)
    private val centroidIndices = intArrayOf(0, 1, 5, 9, 13, 17)
    private val invisibleLeftHand = NativeDetectedHand(label = "left", visible = false)
    private val invisibleRightHand = NativeDetectedHand(label = "right", visible = false)
    private val emptyHands = listOf(invisibleLeftHand, invisibleRightHand)

    /**
     * 同步初始化入口。
     *
     * 这个入口只用于已经确认上下文就绪、且允许同步创建 landmarker 的场景。
     */
    fun ensureReady() {
        if (landmarker != null) {
            return
        }

        landmarker = buildLandmarker()
    }

    /**
     * 绑定实时检测回调。
     *
     * 这层只保存一组稳定回调，不保存任何页面状态，也不缓存具体帧数据。
     * CameraX 和上层会话负责决定何时开始、何时停止，这里只负责把模型结果回传出去。
     */
    fun bindCallbacks(
        onDetection: ((List<NativeDetectedHand>, FrameDisplayTransform, Long) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        this.onDetectionCallback = onDetection
        this.onErrorCallback = onError
    }

    /**
     * 在后台线程初始化 MediaPipe，避免模型加载阻塞主线程。
     */
    fun ensureReadyAsync(timeoutMs: Long, onReady: () -> Unit, onError: (String) -> Unit) {
        synchronized(initLock) {
            if (landmarker != null) {
                mainHandler.post(onReady)
                return
            }

            pendingInitCallbacks.add(PendingInitCallback(onReady, onError))
            if (initializationRunning) {
                return
            }

            initializationRunning = true
            initGeneration += 1
            val currentGeneration = initGeneration

            timeoutExecutor.schedule(
                {
                    handleInitTimeout(currentGeneration, timeoutMs)
                },
                timeoutMs,
                TimeUnit.MILLISECONDS
            )

            initExecutor.execute {
                try {
                    val createdLandmarker = buildLandmarker()
                    mainHandler.post {
                        handleInitSuccess(currentGeneration, createdLandmarker)
                    }
                } catch (error: Throwable) {
                    mainHandler.post {
                        handleInitError(currentGeneration, normalizeErrorMessage(error, "MediaPipe 初始化失败"))
                    }
                }
            }
        }
    }

    /**
     * 直接接收 CameraX 的 ImageProxy。
     *
     * 这里只走 CameraX 的 RGBA_8888 直通路径：
     * 1. 校验输入格式；
     * 2. 用 MediaImageBuilder 构建 MPImage；
     * 3. 提交给 HandLandmarker 做异步检测。
     */
	@OptIn(ExperimentalGetImage::class)
    fun detect(imageProxy: ImageProxy, timestampMs: Long, analyzeStartedAt: Long) : Boolean {
        val currentLandmarker = landmarker ?: throw IllegalStateException("Android HandLandmarker 尚未初始化")
        validateInputFormat(imageProxy)
        // live stream 只保留一个正在处理中的输入帧；如果上一帧还没回调回来，就直接丢掉当前最新帧，
        // 让 CameraX 的 KEEP_ONLY_LATEST 去提供下一次可用的新帧，避免结果队列继续堆积。
        if (!tryAcquireDetectionSlot()) {
            return false
        }

        val frameTransform = FrameDisplayTransform(
            analysisWidth = imageProxy.width,
            analysisHeight = imageProxy.height,
            cropLeft = imageProxy.cropRect.left,
            cropTop = imageProxy.cropRect.top,
            cropRight = imageProxy.cropRect.right,
            cropBottom = imageProxy.cropRect.bottom
        )

        val detectStartedAt = SystemClock.uptimeMillis()
        var mpImage: MPImage? = null
        var submitted = false
        try {
            val mediaImage = imageProxy.image ?: throw IllegalStateException("CameraX 直通 Image 为空")
            mpImage = MediaImageBuilder(mediaImage).build()
            currentLandmarker.detectAsync(mpImage ?: throw IllegalStateException("MPImage 构建失败"), timestampMs)
            submitted = true

            val submitCompletedAt = SystemClock.uptimeMillis()
            analyzeToSubmitBenchmark.record(submitCompletedAt - analyzeStartedAt)
            registerPendingFrameTiming(
                PendingFrameTiming(
                    detectSubmittedAt = detectStartedAt,
                    submitCompletedAt = submitCompletedAt,
                    frameTimestampMs = timestampMs,
                    transform = frameTransform,
                    mpImage = mpImage ?: throw IllegalStateException("MPImage 构建失败")
                )
            )
        } finally {
            if (!submitted) {
                try {
                    // 只有在 detectAsync 还没成功提交时，才由调用方负责关闭 MPImage。
                    mpImage?.close()
                } catch (_: Throwable) {
                }
                releaseDetectionSlot()
            }
        }

        return true
    }

    /**
     * 关闭 detector 并释放所有缓存状态。
     *
     * 这里是生命周期的收口点，既清理 landmarker，也清理挂起回调和性能统计。
     */
    fun close() {
        try {
            landmarker?.close()
        } finally {
            clearPendingFrameTiming()?.let {
                closePendingFrameImage(it)
            }
            landmarker = null
            onDetectionCallback = null
            onErrorCallback = null
            detectionInFlight = false
            analyzeToSubmitBenchmark.flush()
            submitToResultBenchmark.flush()
            synchronized(initLock) {
                initializationRunning = false
                pendingInitCallbacks.clear()
                initGeneration += 1
            }
            initExecutor.shutdownNow()
            timeoutExecutor.shutdownNow()
        }
    }

    /**
     * 初始化相关的内部构建入口。
     *
     * 这一段只负责把 HandLandmarker 建出来，不参与帧处理和结果回调。
     * 模型路径已经在构造时固定为 `apps/{appId}/www/static/mediapipe/models/hand_landmarker.task`，
     * 这里不再做递归扫描，只保留一次轻量存在性检查。
     */
    private fun buildLandmarker(): HandLandmarker {
        if (!isAssetAvailable(modelAssetPath)) {
            throw IllegalStateException("MediaPipe 模型资源未找到：" + modelAssetPath)
        }

        return createLandmarker(modelAssetPath)
    }

    private fun createLandmarker(assetPath: String): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(assetPath)
            .setDelegate(Delegate.GPU)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.35f)
            .setResultListener { result, inputImage ->
                handleLiveStreamResult(result, inputImage)
            }
            .setErrorListener { message ->
                handleLiveStreamError(message.toString())
            }
            .build()

        return HandLandmarker.createFromOptions(appContext, options)
    }

    /**
     * 由 appId 拼出固定的模型资源路径。
     *
     * 当前项目的模型文件已经固定打进 `apps/{appId}/www/static/mediapipe/models/hand_landmarker.task`，
     * 所以这里不再做运行时猜路径，只做一次显式拼接。
     */
    private fun buildModelAssetPath(appId: String): String {
        if (appId.isBlank()) {
            throw IllegalArgumentException("应用 appId 不能为空，无法定位 MediaPipe 模型资源")
        }

        return "apps/" + appId + "/www/static/mediapipe/models/hand_landmarker.task"
    }

    /**
     * 检查单个 assets 路径是否存在。
     *
     * 这里只做存在性预检，不读取内容本身。
     */
    private fun isAssetAvailable(assetPath: String): Boolean {
        return try {
            appContext.assets.open(assetPath).close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 结果回调入口。
     *
     * 这里负责把 MediaPipe 的异步结果和提交时记录的帧信息重新对上，
     * 然后分发给页面侧回调。
     */
    private fun handleLiveStreamResult(result: HandLandmarkerResult, inputImage: MPImage) {
        val callback = this.onDetectionCallback
        val resultCallbackStartedAt = SystemClock.uptimeMillis()
        val resultTimestampMs = result.timestampMs()
        val pendingFrameTiming = consumePendingFrameTiming(resultTimestampMs)

        try {
            val timing = pendingFrameTiming
            if (timing == null) {
                Log.w(MEDIAPIPE_LOG_TAG, "MediaPipe 结果已过期")
                return
            }

            submitToResultBenchmark.record(resultCallbackStartedAt - timing.detectSubmittedAt)

            val frameTransform = timing.transform
            val hands = mapResult(result, frameTransform)
            if (callback != null) {
                callback(hands, frameTransform, resultTimestampMs)
            }
        } finally {
            try {
                inputImage.close()
            } catch (_: Throwable) {
            }
            if (pendingFrameTiming != null) {
                releaseDetectionSlot()
            }
        }
    }

    /**
     * 结果回调中的错误入口。
     *
     * 这里直接清理当前帧状态并把错误通知给上层，不再额外包装业务逻辑。
     */
    private fun handleLiveStreamError(message: String) {
        Log.e(MEDIAPIPE_LOG_TAG, message)
        clearPendingFrameTiming()?.let {
            closePendingFrameImage(it)
        }
        releaseDetectionSlot()
        val callback = this.onErrorCallback
        if (callback != null) {
            mainHandler.post {
                callback(message)
            }
        }
    }

  /**
   * 初始化成功后的收口。
   *
   * 只有 generation 仍然有效时，这次创建的 landmarker 才会被接收。
   */
    private fun handleInitSuccess(generation: Int, createdLandmarker: HandLandmarker) {
        val callbacks = synchronized(initLock) {
            if (!initializationRunning || generation != initGeneration) {
                null
            } else {
                initializationRunning = false
                landmarker = createdLandmarker
                pendingInitCallbacks.toList().also {
                    pendingInitCallbacks.clear()
                }
            }
        }

        if (callbacks == null) {
            try {
                createdLandmarker.close()
            } catch (_: Throwable) {
            }
            return
        }

        callbacks.forEach { it.onReady.invoke() }
    }

  /**
   * 初始化失败后的收口。
   *
   * 如果这次失败对应的初始化已经失效，就只记日志，不再把错误回抛给旧的等待者。
   */
    private fun handleInitError(generation: Int, message: String) {
        val callbacks = synchronized(initLock) {
            if (!initializationRunning || generation != initGeneration) {
                null
            } else {
                initializationRunning = false
                pendingInitCallbacks.toList().also {
                    pendingInitCallbacks.clear()
                }
            }
        }

        if (callbacks == null) {
            return
        }

        Log.e(MEDIAPIPE_LOG_TAG, message)
        callbacks.forEach { it.onError.invoke(message) }
    }

  /**
   * 初始化超时的收口。
   *
   * 只有 generation 仍然有效时，这次超时才会通知等待中的回调。
   */
    private fun handleInitTimeout(generation: Int, timeoutMs: Long) {
        val callbacks = synchronized(initLock) {
            if (!initializationRunning || generation != initGeneration) {
                null
            } else {
                initializationRunning = false
                initGeneration += 1
                pendingInitCallbacks.toList().also {
                    pendingInitCallbacks.clear()
                }
            }
        }

        if (callbacks == null) {
            return
        }

        val message = "MediaPipe 初始化超时（>${timeoutMs}ms）"
        Log.e(MEDIAPIPE_LOG_TAG, message)
        callbacks.forEach { it.onError.invoke(message) }
    }

    /**
     * 把异常信息归一成可展示文案。
     *
     * 当 Throwable 本身没有 message 时，用 fallback 保底。
     */
    private fun normalizeErrorMessage(error: Throwable, fallback: String): String {
        val message = error.message
        if (!message.isNullOrEmpty()) {
            return message
        }
        return fallback
    }

    /**
     * 申请一个检测槽位。
     *
     * live stream 同一时刻只允许一个输入帧在飞，避免结果回调堆积。
     */
    private fun tryAcquireDetectionSlot() : Boolean {
        synchronized(detectionLock) {
            if (detectionInFlight) {
                return false
            }

            detectionInFlight = true
            return true
        }
    }

    /**
     * 释放检测槽位。
     *
     * 与 tryAcquireDetectionSlot 成对使用，保证异常和正常路径都能回收状态。
     */
    private fun releaseDetectionSlot() {
        synchronized(detectionLock) {
            detectionInFlight = false
        }
    }

  /**
   * 记录当前这次提交对应的挂起帧，并为它注册结果超时 watchdog。
   *
   * live stream 只允许一个输入帧在飞，所以这里会覆盖旧的挂起状态。
   * 如果已有挂起帧，就关闭旧的 MPImage，再挂上新的帧，避免引用泄漏。
   */
    private fun registerPendingFrameTiming(timing: PendingFrameTiming) {
        val previousTiming = synchronized(detectionLock) {
            pendingFrameTimeoutFuture?.cancel(false)
            pendingFrameTimeoutFuture = timeoutExecutor.schedule(
                {
                    handlePendingFrameTimeout(timing.submitCompletedAt, timing.frameTimestampMs)
                },
                RESULT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            val previous = pendingFrameTiming
            pendingFrameTiming = timing
            previous
        }

        if (previousTiming != null) {
            closePendingFrameImage(previousTiming)
        }
    }

    /**
     * 消费一条与当前回调匹配的挂起帧。
     *
     * 如果结果回调是迟到的旧结果，直接返回 null，并保留当前新帧状态不变，
     * 防止旧回调误清掉新的检测槽位。
     */
    private fun consumePendingFrameTiming(resultTimestampMs: Long): PendingFrameTiming? {
        return synchronized(detectionLock) {
            val timing = pendingFrameTiming ?: return@synchronized null
            if (timing.frameTimestampMs != resultTimestampMs) {
                return@synchronized null
            }

            pendingFrameTiming = null
            pendingFrameTimeoutFuture?.cancel(false)
            pendingFrameTimeoutFuture = null
            timing
        }
    }

    /**
     * 清空当前挂起帧，但不区分来源。
     *
     * 这个入口用于错误、关闭等兜底路径，确保 watchdog 和挂起状态一起被清掉。
     */
    private fun clearPendingFrameTiming() : PendingFrameTiming? {
        return synchronized(detectionLock) {
            val timing = pendingFrameTiming
            pendingFrameTiming = null
            pendingFrameTimeoutFuture?.cancel(false)
            pendingFrameTimeoutFuture = null
            timing
        }
    }

    /**
     * 处理检测结果超时。
     *
     * 只有当超时任务仍然对应当前挂起帧时，才真正释放槽位并关闭 MPImage。
     */
    private fun handlePendingFrameTimeout(submitCompletedAt: Long, frameTimestampMs: Long) {
        val timing = synchronized(detectionLock) {
            val currentTiming = pendingFrameTiming ?: return@synchronized null
            if (currentTiming.submitCompletedAt != submitCompletedAt || currentTiming.frameTimestampMs != frameTimestampMs) {
                return@synchronized null
            }

            pendingFrameTiming = null
            pendingFrameTimeoutFuture = null
            currentTiming
        } ?: return

        val waitCostMs = SystemClock.uptimeMillis() - timing.submitCompletedAt
        Log.w(MEDIAPIPE_LOG_TAG, "MediaPipe 结果超时，wait=${waitCostMs}ms")
        closePendingFrameImage(timing)
        releaseDetectionSlot()
    }

    /**
     * 统一关闭挂起帧持有的 MPImage。
     */
    private fun closePendingFrameImage(timing: PendingFrameTiming) {
        try {
            timing.mpImage.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * 将一次 HandLandmarker 结果整理为左右手固定槽位。
     *
     * 页面层只消费稳定的 left / right 结构，不直接处理底层索引。
     */
    private fun mapResult(
        result: HandLandmarkerResult,
        transform: FrameDisplayTransform
    ): List<NativeDetectedHand> {
        var leftHand: NativeDetectedHand? = null
        var rightHand: NativeDetectedHand? = null
        val landmarksList = result.landmarks()
        val handednessList = result.handedness()
        val count = landmarksList?.size ?: 0

        for (i in 0 until count) {
            val landmarks = landmarksList?.get(i)
            val handedness = handednessList?.getOrNull(i)
            val hand = toDetectedHand(landmarks, handedness, transform.analysisWidth, transform.analysisHeight) ?: continue
            if (hand.label == "left") {
                leftHand = hand
            } else if (hand.label == "right") {
                rightHand = hand
            }
        }

        if (leftHand == null && rightHand == null) {
            return emptyHands
        }

        return listOf(
            leftHand ?: invisibleLeftHand,
            rightHand ?: invisibleRightHand
        )
    }

    /**
     * 把底层结果转换为原生侧统一结构。
     *
     * 这里只负责数据转换，不决定页面展示。
     */
    private fun toDetectedHand(
        landmarks: List<NormalizedLandmark>?,
        handedness: List<Category>?,
        width: Int,
        height: Int
    ): NativeDetectedHand? {
        if (landmarks == null || handedness == null || landmarks.isEmpty() || handedness.isEmpty()) {
            return null
        }

        val category = handedness.firstOrNull() ?: return null
        val rawLabel = category.categoryName()
        val label = when {
            rawLabel.equals("left", ignoreCase = true) -> "left"
            rawLabel.equals("right", ignoreCase = true) -> "right"
            else -> return null
        }
        if (label != "left" && label != "right") {
            return null
        }

        val centroid = calculateCentroid(landmarks)
        return NativeDetectedHand(
            label = label,
            visible = true,
            confidence = category.score().toDouble(),
            centerX = centroid.x * width,
            centerY = centroid.y * height,
            size = maxOf(centroid.size * maxOf(width.toDouble(), height.toDouble()), 1.0)
        )
    }

  /**
   * 从 landmarks 里计算手部质心和尺度。
   *
   * 这里优先用一组稳定关节点做近似，在必要时退回到全量点扫描。
   */
    private fun calculateCentroid(landmarks: List<NormalizedLandmark>): HandCentroid {
        var sumX = 0.0
        var sumY = 0.0
        var validCount = 0
        var minX = 1.0
        var minY = 1.0
        var maxX = 0.0
        var maxY = 0.0

        for (index in centroidIndices) {
            if (index < 0 || index >= landmarks.size) {
                continue
            }
            val point = landmarks[index]
            val x = point.x().toDouble()
            val y = point.y().toDouble()
            if (!x.isFinite() || !y.isFinite()) {
                continue
            }

            sumX += x
            sumY += y
            validCount += 1
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }

        if (validCount <= 0) {
            for (point in landmarks) {
                val x = point.x().toDouble()
                val y = point.y().toDouble()
                if (!x.isFinite() || !y.isFinite()) {
                    continue
                }

                sumX += x
                sumY += y
                validCount += 1
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }

        if (validCount <= 0) {
            return HandCentroid(0.5, 0.5, 0.0)
        }

        return HandCentroid(
            x = sumX / validCount.toDouble(),
            y = sumY / validCount.toDouble(),
            size = maxOf(maxX - minX, maxY - minY)
        )
    }

    /**
     * 校验 CameraX 分析帧格式。
     *
     * 这里把 RGBA_8888 作为唯一合法输入，避免后面还要处理多种图像分支。
     */
    private fun validateInputFormat(imageProxy: ImageProxy) {
        val imageProxyFormat = imageProxy.format
        if (imageProxyFormat == PixelFormat.RGBA_8888 || imageProxyFormat == ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) {
            return
        }

        throw IllegalStateException(
            "CameraX 分析帧输入不符合 MediaPipe 要求：期望 RGBA_8888，" +
                "实际 ImageProxy=${describeImageFormat(imageProxyFormat)}"
        )
    }

    /**
     * 把底层图像格式转成可读名字。
     */
    private fun describeImageFormat(format: Int): String {
        return when (format) {
            PixelFormat.RGBA_8888 -> "RGBA_8888"
            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 -> "RGBA_8888"
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.NV21 -> "NV21"
            else -> "UNKNOWN($format)"
        }
    }

}

private const val MEDIAPIPE_LOG_TAG: String = "hand-tracker-mp"
private const val RESULT_TIMEOUT_MS: Long = 300L

private data class PendingInitCallback(
    val onReady: () -> Unit,
    val onError: (String) -> Unit
)

private data class PendingFrameTiming(
    val detectSubmittedAt: Long,
    val submitCompletedAt: Long,
    val frameTimestampMs: Long,
    val transform: FrameDisplayTransform,
    val mpImage: MPImage
)

private class LatencyWindow(
    private val stageName: String,
    private val logTag: String,
    private val windowSize: Int = 120
) {
    private val samples = LongArray(windowSize)
    private var sampleCount: Int = 0

    fun record(durationMs: Long) {
        if (durationMs < 0) {
            return
        }

        samples[sampleCount] = durationMs
        sampleCount += 1
        if (sampleCount >= windowSize) {
            flush()
        }
    }

    fun flush() {
        if (sampleCount <= 0) {
            return
        }

        val sorted = samples.copyOf(sampleCount)
        Arrays.sort(sorted)
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val p99 = percentile(sorted, 0.99)
        val max = sorted[sorted.lastIndex]
        Log.i(
            logTag,
            "Latency bench stage=$stageName samples=${sorted.size}, p50=${p50}ms, p95=${p95}ms, p99=${p99}ms, max=${max}ms"
        )
        sampleCount = 0
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long {
        if (sorted.isEmpty()) {
            return 0
        }

        val index = ((sorted.size - 1) * fraction).toInt()
        return sorted[index]
    }
}
