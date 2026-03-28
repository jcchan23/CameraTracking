import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * uni-app x 项目采用最小插件链。
 *
 * 标准结构下只保留 uni 插件，让 .uvue/.uts 交给 uni-app x 编译器处理。
 */
export default defineConfig({
  plugins: [uni()]
})
