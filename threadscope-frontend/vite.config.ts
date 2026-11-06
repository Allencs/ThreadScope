import { readFileSync } from 'fs'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'
import { resolve } from 'path'

/** 仓库根目录的手册，开发时直接送出，构建时打进 dist，避免再复制一份。 */
const JFR_MANUAL_URL = '/docs/jfr-advanced-manual.html'

function jfrManualPlugin(): Plugin {
  const manualPath = resolve(__dirname, '../docs/jfr-advanced-manual.html')
  const load = () => readFileSync(manualPath)
  return {
    name: 'jfr-manual',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== JFR_MANUAL_URL) return next()
        res.setHeader('Content-Type', 'text/html; charset=utf-8')
        res.end(load())
      })
    },
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: JFR_MANUAL_URL.slice(1),
        source: load(),
      })
    },
  }
}

export default defineConfig({
  plugins: [
    vue(),
    UnoCSS(),
    jfrManualPlugin(),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
