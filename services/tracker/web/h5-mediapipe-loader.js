import { FilesetResolver, HandLandmarker } from '@mediapipe/tasks-vision'

/**
 * 这里固定走 static 目录而不是直接引用 node_modules 静态路径，
 * 是为了让开发环境和生产构建都使用同一套资源访问方式，减少路径不一致问题。
 */
const WASM_ROOT = '/static/mediapipe/wasm'
const MODEL_ASSET_PATH = '/static/mediapipe/models/hand_landmarker.task'
const DETECTOR_OPTIONS = {
  runningMode: 'VIDEO',
  numHands: 2,
  minHandDetectionConfidence: 0.45,
  minHandPresenceConfidence: 0.45,
  minTrackingConfidence: 0.35
}

let detectorInstance = null
let detectorPromise = null
let detectorGeneration = 0
let activeDelegate = 'CPU'
let gpuCanvas = null
const DETECTOR_WARMUP_CANCELLED_ERROR = '__HAND_LANDMARKER_WARMUP_CANCELLED__'

/**
 * 当前实际使用的 delegate 会回传给页面调试面板，
 * 让页面知道自己是在 GPU 还是 CPU 路径下运行。
 */
export function getActiveDelegate() {
  return activeDelegate
}

/**
 * 预热 detector。
 *
 * 这个函数保留单一职责：尽早触发 detector 创建，但不改变缓存和回退策略。
 * 会话层可以在摄像头申请阶段就调用它，把 GPU / CPU 初始化和媒体流申请并行起来。
 */
export function prewarmHandLandmarker() {
  return ensureHandLandmarker()
}

/**
 * detector 在 H5 里属于比较重的资源，
 * 因此这里使用单例缓存，避免页面轻微重绘时反复初始化模型。
 */
export async function ensureHandLandmarker() {
  if (detectorInstance) {
    return detectorInstance
  }

  if (!detectorPromise) {
    const requestGeneration = detectorGeneration
    detectorPromise = createPreferredDetector()
      .then((detector) => {
        if (requestGeneration !== detectorGeneration) {
          closeDetectorSafely(detector)
          throw new Error(DETECTOR_WARMUP_CANCELLED_ERROR)
        }

        detectorInstance = detector
        return detector
      })
      .catch((error) => {
        detectorPromise = null
        throw error
      })
  }

  return detectorPromise
}

/**
 * 页面停止追踪或发生严重异常时，需要统一释放 detector。
 * 这一步同时会把当前 delegate 状态恢复成默认 CPU，
 * 避免后续调试信息沿用上一次已经失效的后端值。
 */
export function destroyHandLandmarker() {
  detectorGeneration += 1

  if (detectorInstance && typeof detectorInstance.close === 'function') {
    detectorInstance.close()
  }

  detectorInstance = null
  detectorPromise = null
  activeDelegate = 'CPU'
  cleanupGpuCanvas()
}

async function createPreferredDetector() {
  if (canUseGpuDelegate()) {
    try {
      return await createDetector('GPU')
    } catch (error) {
      cleanupGpuCanvas()
    }
  }

  return createDetector('CPU')
}

/**
 * 创建真正的 MediaPipe HandLandmarker。
 *
 * MODEL_ASSET_PATH 指向的是随项目一起发布的 task 文件；
 * WASM_ROOT 指向同步到 static 的 wasm 目录；
 * 二者都必须是浏览器可直接访问到的静态资源路径。
 */
async function createDetector(delegate) {
  const vision = await FilesetResolver.forVisionTasks(WASM_ROOT)
  const options = {
    baseOptions: {
      modelAssetPath: MODEL_ASSET_PATH,
      delegate
    },
    ...DETECTOR_OPTIONS
  }

  if (delegate === 'GPU') {
    options.canvas = ensureGpuCanvas()
  }

  const detector = await HandLandmarker.createFromOptions(vision, options)
  activeDelegate = delegate
  return detector
}

/**
 * 这里只检查“浏览器至少具备 WebGL2 环境”，
 * 并不等于 GPU delegate 一定可用。
 * 真正的运行时失败仍然会在 detect 阶段由上层会话处理。
 */
function canUseGpuDelegate() {
  if (typeof document === 'undefined' || typeof HTMLCanvasElement === 'undefined') {
    return false
  }

  const testCanvas = document.createElement('canvas')
  const context = testCanvas.getContext('webgl2', {
    alpha: false,
    antialias: false,
    depth: false,
    preserveDrawingBuffer: false,
    stencil: false
  })

  if (!context) {
    return false
  }

  const loseContext = context.getExtension('WEBGL_lose_context')
  if (loseContext && typeof loseContext.loseContext === 'function') {
    loseContext.loseContext()
  }

  return true
}

/**
 * MediaPipe 的 GPU delegate 需要一个 canvas 挂载 WebGL 上下文。
 * 这个 canvas 不参与页面显示，只是 detector 初始化时的底层依赖。
 */
function ensureGpuCanvas() {
  if (gpuCanvas) {
    return gpuCanvas
  }

  gpuCanvas = document.createElement('canvas')
  gpuCanvas.width = 1
  gpuCanvas.height = 1
  gpuCanvas.style.position = 'fixed'
  gpuCanvas.style.left = '-9999px'
  gpuCanvas.style.top = '-9999px'
  gpuCanvas.style.width = '1px'
  gpuCanvas.style.height = '1px'
  gpuCanvas.style.opacity = '0'
  gpuCanvas.style.pointerEvents = 'none'
  document.body.appendChild(gpuCanvas)
  return gpuCanvas
}

function cleanupGpuCanvas() {
  if (!gpuCanvas) {
    return
  }

  if (gpuCanvas.parentNode) {
    gpuCanvas.parentNode.removeChild(gpuCanvas)
  }

  gpuCanvas = null
}

function closeDetectorSafely(detector) {
  if (detector && typeof detector.close === 'function') {
    detector.close()
  }
}
