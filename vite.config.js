import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * 当前工程收敛为 uni-app x 的 H5 结构后，Vite 只保留 uni 插件。
 *
 * 这样可以避免重新引入 Vue 单独解析器、Android 分支补丁以及旧 uni-app
 * 的额外启动假设，让构建链路回到最小可维护状态。
 */
export default defineConfig({
  plugins: [uni()]
})
