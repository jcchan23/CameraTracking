import { destroyHandLandmarker, ensureHandLandmarker, getActiveDelegate } from './h5-mediapipe-loader.js'

/**
 * 会话级常量与预览样式配置。
 *
 * 这一段定义的是 H5 检测链路运行时要用到的基础配置：
 * - 轮询间隔
 * - 预览 video 显示时的样式
 * - 预览 video 隐藏时的样式
 *
 * 把这些配置集中在顶部，方便维护者快速知道“页面为什么会看到视频”以及
 * “video 为什么有时在屏幕内、有时被挪到屏幕外”。
 */
const FALLBACK_FRAME_INTERVAL_MS = 33
const PREVIEW_VISIBLE_STYLE = {
  position: 'fixed',
  left: '0',
  top: '0',
  width: '100vw',
  height: '100vh',
  opacity: '1',
  objectFit: 'cover',
  pointerEvents: 'none',
  zIndex: '1',
  transform: 'scaleX(-1)',
  backgroundColor: '#08111f'
}
const PREVIEW_HIDDEN_STYLE = {
  position: 'fixed',
  left: '-9999px',
  top: '-9999px',
  width: '1px',
  height: '1px',
  opacity: '0',
  objectFit: 'cover',
  pointerEvents: 'none',
  zIndex: '-1',
  transform: 'none',
  backgroundColor: 'transparent'
}

/**
 * 创建 H5 手部检测会话。
 *
 * 返回值不是 detector 本身，而是一组会话方法：
 * - start：申请摄像头、准备 video、启动检测循环
 * - stop：停止检测、释放媒体流和 detector
 *
 * 维护者可以把这里理解成“浏览器端追踪链路的总控器”。
 */
export function createH5HandTrackerSession() {
  let stopped = true
  let mediaStream = null
  let videoElement = null
  let timerId = null
  let frameRequestId = null
  let inFlight = false
  let lastMediaTime = -1

  return {
    async start(options) {
      const { viewportProvider, onFrame, onError } = options
      stopped = false
      lastMediaTime = -1

      try {
        /**
         * 启动顺序不能轻易调整：
         * 1. 先确认浏览器支持摄像头；
         * 2. 再准备 video；
         * 3. 再申请媒体流；
         * 4. 最后才启动 detect 调度。
         *
         * 如果跳过其中任一步，最容易出现“video 已创建但没有真实画面”或
         * “detector 启动了但读不到视频流”的问题。
         */
        ensureBrowserSupport()
        videoElement = ensureHiddenVideo()
        mediaStream = await navigator.mediaDevices.getUserMedia({
          audio: false,
          video: {
            facingMode: 'user',
            width: { ideal: 1920 },
            height: { ideal: 1080 },
            frameRate: {
              ideal: 30,
              max: 30
            }
          }
        })

        videoElement.srcObject = mediaStream
        syncPreviewVideoVisibility(videoElement, true)
        await waitForVideoReady(videoElement)
        await videoElement.play()

        scheduleNextTick()

        /**
         * 真正的单帧检测逻辑。
         *
         * 输入：
         * - 当前 videoElement 上的新视频帧
         * - viewportProvider 提供的当前视口尺寸
         *
         * 输出：
         * - 通过 onFrame 把 hands + runtime 回传给上层适配器
         *
         * 关键点：
         * - inFlight 防止多帧同时推理；
         * - GPU 出错时会在这里回退到 CPU；
         * - 每次成功或失败后都要考虑下一帧调度。
         */
        async function runDetect() {
          if (stopped || inFlight) {
            return
          }

          /**
           * 避免多帧并发推理造成结果乱序。
           */
          inFlight = true
          try {
            const viewport = viewportProvider()
            const detector = await ensureHandLandmarker()
            const result = detector.detectForVideo(videoElement, performance.now())
            const runtime = buildRuntimeInfo(mediaStream, getActiveDelegate())
            runtime.message = 'H5 MediaPipe 手部追踪运行中。'
            onFrame({
              hands: mapMediaPipeResultToDetectedHands(result, viewport),
              runtime
            })
          } catch (error) {
            /**
             * GPU 路径失败后，立即回退到 CPU 重建 detector。
             */
            if (getActiveDelegate() === 'GPU') {
              try {
                destroyHandLandmarker()
                const detector = await ensureHandLandmarker()
                const viewport = viewportProvider()
                const result = detector.detectForVideo(videoElement, performance.now())
                const runtime = buildRuntimeInfo(mediaStream, getActiveDelegate())
                runtime.message = 'H5 MediaPipe 已自动从 GPU 回退到 CPU。'
                onFrame({
                  hands: mapMediaPipeResultToDetectedHands(result, viewport),
                  runtime
                })
              } catch (fallbackError) {
                onError(normalizeErrorMessage(fallbackError))
                stopInternal()
                return
              }
            } else {
              onError(normalizeErrorMessage(error))
              stopInternal()
              return
            }
          } finally {
            inFlight = false
          }

          scheduleNextTick()
        }

        /**
         * 安排下一次检测。
         *
         * 优先使用 requestVideoFrameCallback 跟随真实出帧，
         * 这样可以减少“没有新画面却重复推理”的空跑。
         * 只有浏览器不支持时，才退回固定间隔轮询。
         */
        function scheduleNextTick() {
          if (stopped) {
            return
          }

          /**
           * 优先跟随真实视频出帧，不支持时退回固定间隔轮询。
           */
          if (videoElement && typeof videoElement.requestVideoFrameCallback === 'function') {
            frameRequestId = videoElement.requestVideoFrameCallback((_, metadata = {}) => {
              frameRequestId = null
              const mediaTime = Number(metadata.mediaTime)
              if (Number.isFinite(mediaTime) && mediaTime === lastMediaTime) {
                scheduleNextTick()
                return
              }

              if (Number.isFinite(mediaTime)) {
                lastMediaTime = mediaTime
              }

              runDetect()
            })
            return
          }

          timerId = window.setTimeout(() => {
            timerId = null
            runDetect()
          }, FALLBACK_FRAME_INTERVAL_MS)
        }
      } catch (error) {
        onError(normalizeErrorMessage(error))
        stopInternal()
      }
    },
    stop() {
      stopInternal()
    }
  }

  /**
   * 统一释放会话资源。
   *
   * 这里要收的资源包括：
   * - 定时器
   * - 视频帧回调
   * - 媒体流 track
   * - video 元素上的 srcObject
   * - MediaPipe detector
   *
   * 这一步不能省略，否则页面反复进入/退出时很容易留下摄像头占用或旧 detector 实例。
   */
  function stopInternal() {
    stopped = true

    if (timerId !== null) {
      window.clearTimeout(timerId)
      timerId = null
    }

    if (videoElement && frameRequestId !== null && typeof videoElement.cancelVideoFrameCallback === 'function') {
      videoElement.cancelVideoFrameCallback(frameRequestId)
      frameRequestId = null
    }

    if (mediaStream) {
      mediaStream.getTracks().forEach((track) => track.stop())
      mediaStream = null
    }

    if (videoElement) {
      videoElement.pause()
      videoElement.srcObject = null
      syncPreviewVideoVisibility(videoElement, false)
    }

    destroyHandLandmarker()
  }
}

/**
 * 检查当前浏览器是否具备摄像头能力。
 */
function ensureBrowserSupport() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    throw new Error('当前浏览器不支持摄像头')
  }
}

/**
 * 获取或创建隐藏 video。
 *
 * 这里虽然函数名里写的是 hiddenVideo，但在 H5 当前方案中，
 * 同一个 video 元素会在“隐藏输入源”和“可见预览层”之间切换样式。
 * 这样做的好处是预览与检测共用一条媒体流，不会重复申请摄像头。
 */
function ensureHiddenVideo() {
  let element = document.getElementById('tracking-hidden-video')
  if (element instanceof HTMLVideoElement) {
    syncPreviewVideoVisibility(element, false)
    return element
  }

  element = document.createElement('video')
  element.id = 'tracking-hidden-video'
  element.muted = true
  element.autoplay = true
  element.playsInline = true
  syncPreviewVideoVisibility(element, false)
  document.body.appendChild(element)
  return element
}

/**
 * 切换 video 的显示状态。
 *
 * visible = true：
 * - video 作为页面可见预览层
 *
 * visible = false：
 * - video 继续保留为 detector 输入源，但不占据页面可见区域
 */
function syncPreviewVideoVisibility(videoElement, visible) {
  const targetStyle = visible ? PREVIEW_VISIBLE_STYLE : PREVIEW_HIDDEN_STYLE
  Object.keys(targetStyle).forEach((styleKey) => {
    videoElement.style[styleKey] = targetStyle[styleKey]
  })
}

/**
 * 等待 video 真正进入可播放状态。
 *
 * getUserMedia 成功不代表 video 立即可用，
 * 所以这里要等 loadedmetadata / canplay 事件后再继续启动检测。
 */
function waitForVideoReady(videoElement) {
  if (videoElement.readyState >= 2) {
    return Promise.resolve()
  }

  return new Promise((resolve, reject) => {
    const cleanup = () => {
      videoElement.removeEventListener('loadedmetadata', handleReady)
      videoElement.removeEventListener('canplay', handleReady)
      videoElement.removeEventListener('error', handleError)
    }

    const handleReady = () => {
      cleanup()
      resolve()
    }

    const handleError = () => {
      cleanup()
      reject(new Error('摄像头视频流尚未准备完成'))
    }

    videoElement.addEventListener('loadedmetadata', handleReady, { once: true })
    videoElement.addEventListener('canplay', handleReady, { once: true })
    videoElement.addEventListener('error', handleError, { once: true })
  })
}

/**
 * 生成调试面板需要的运行时信息。
 *
 * 这里读取的是浏览器最终协商后的真实输入参数，
 * 而不是 getUserMedia 请求时的 ideal 参数，
 * 这样页面展示出来的信息才和实际运行情况一致。
 */
function buildRuntimeInfo(stream, delegate) {
  const videoTrack = stream && typeof stream.getVideoTracks === 'function' ? stream.getVideoTracks()[0] : null
  const videoTrackSettings = videoTrack && typeof videoTrack.getSettings === 'function' ? videoTrack.getSettings() : {}
  const width = Number(videoTrackSettings.width) || 0
  const height = Number(videoTrackSettings.height) || 0
  const frameRate = Number(videoTrackSettings.frameRate) || 0
  const backendName = delegate || 'CPU'

  return {
    backendName,
    backendLabel: '后端: ' + backendName,
    fpsLabel: frameRate > 0 ? ('帧率: ' + formatRate(frameRate)) : '帧率: --',
    resolutionLabel: width > 0 && height > 0 ? ('分辨率: ' + width + 'x' + height) : '分辨率: H5 video',
    message: ''
  }
}

function formatRate(frameRate) {
  return Number.isInteger(frameRate) ? String(frameRate) : frameRate.toFixed(1)
}

/**
 * 把各种异常对象统一整理成用户可读的错误文案。
 */
function normalizeErrorMessage(error) {
  if (error && typeof error.message === 'string' && error.message.length > 0) {
    return error.message
  }

  return 'H5 手部检测启动失败'
}

/**
 * 将 MediaPipe HandLandmarker 的原始输出映射成页面可消费的手部数据。
 *
 * 这里使用“包围盒中心点 + 包围盒最大边长”作为输入，理由很简单：
 * 页面只需要知道“手在哪里”和“手相对大不大”，不需要完整 landmarks。
 */
function mapMediaPipeResultToDetectedHands(result, viewport) {
  const landmarksList = Array.isArray(result && result.landmarks) ? result.landmarks : []
  const handednessList = Array.isArray(result && result.handedness) ? result.handedness : []

  return landmarksList
    .map((landmarks, index) => normalizeHand(landmarks, handednessList[index], viewport))
    .filter(Boolean)
}

function normalizeHand(landmarks, handednessGroup, viewport) {
  if (!Array.isArray(landmarks) || !landmarks.length || !Array.isArray(handednessGroup) || !handednessGroup.length) {
    return null
  }

  const handedness = handednessGroup[0]
  const label = String(handedness.categoryName || '').toLowerCase()
  if (label !== 'left' && label !== 'right') {
    return null
  }

  let minX = 1
  let minY = 1
  let maxX = 0
  let maxY = 0

  landmarks.forEach((point) => {
    minX = Math.min(minX, point.x)
    minY = Math.min(minY, point.y)
    maxX = Math.max(maxX, point.x)
    maxY = Math.max(maxY, point.y)
  })

  const boxWidth = Math.max(maxX - minX, 0)
  const boxHeight = Math.max(maxY - minY, 0)

  return {
    label,
    confidence: Number(handedness.score || 0),
    /**
     * H5 前摄默认是镜像预览，
     * 所以这里对 x 做一次镜像翻转，确保页面上的手部标记和用户视觉方向一致。
     */
    centerX: clamp(viewport.width - (((minX + maxX) * 0.5) * viewport.width), 0, viewport.width),
    centerY: clamp(((minY + maxY) * 0.5) * viewport.height, 0, viewport.height),
    /**
     * size 不代表真实物理尺寸，而是“手在画面中的相对大小代理值”。
     * 页面层会继续用这个值去做 marker 的远近缩放和平滑过渡。
     */
    size: Math.max(boxWidth, boxHeight)
  }
}

function clamp(value, minValue, maxValue) {
  return Math.min(maxValue, Math.max(minValue, value))
}
