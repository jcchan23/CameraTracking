'use strict'

const fs = require('node:fs')
const path = require('node:path')

/**
 * 统一同步 MediaPipe 的静态资源。
 *
 * 当前脚本做三件事：
 * 1. 校正 tasks-vision 的 sourceMappingURL 注释，避免部分环境报警告；
 * 2. 将 wasm 运行时资源复制到 static 目录，避免依赖 node_modules 暴露静态资源；
 * 3. 确保 hand_landmarker.task 已经放在项目 static 目录下。
 */
function main() {
  const rootDir = process.cwd()
  const packageWasmDir = path.join(rootDir, 'node_modules', '@mediapipe', 'tasks-vision', 'wasm')
  const staticWasmDir = path.join(rootDir, 'static', 'mediapipe', 'wasm')
  const modelTargetPath = path.join(rootDir, 'static', 'mediapipe', 'models', 'hand_landmarker.task')
  const bundlePath = path.join(rootDir, 'node_modules', '@mediapipe', 'tasks-vision', 'vision_bundle.mjs')

  ensureDir(path.dirname(modelTargetPath))
  ensureDir(staticWasmDir)

  if (fs.existsSync(bundlePath)) {
    const wrongMapComment = '//# sourceMappingURL=vision_bundle_mjs.js.map'
    const rightMapComment = '//# sourceMappingURL=vision_bundle.mjs.map'
    const bundleContent = fs.readFileSync(bundlePath, 'utf8')
    if (bundleContent.includes(wrongMapComment)) {
      fs.writeFileSync(bundlePath, bundleContent.replace(wrongMapComment, rightMapComment), 'utf8')
    }
  }

  if (fs.existsSync(packageWasmDir)) {
    copyDirectory(packageWasmDir, staticWasmDir)
  } else {
    console.warn('[sync-mediapipe-assets] 未找到 wasm 目录，跳过 wasm 复制。')
  }

  if (!fs.existsSync(modelTargetPath)) {
    console.warn('[sync-mediapipe-assets] 未找到 hand_landmarker.task，请确认模型文件已存在。')
  }
}

function ensureDir(targetDir) {
  fs.mkdirSync(targetDir, { recursive: true })
}

function copyDirectory(sourceDir, targetDir) {
  ensureDir(targetDir)
  const entries = fs.readdirSync(sourceDir, { withFileTypes: true })

  for (const entry of entries) {
    const sourcePath = path.join(sourceDir, entry.name)
    const targetPath = path.join(targetDir, entry.name)
    if (entry.isDirectory()) {
      copyDirectory(sourcePath, targetPath)
      continue
    }

    fs.copyFileSync(sourcePath, targetPath)
  }
}

main()
