import { defineConfig, createLogger } from 'vite'
import react from '@vitejs/plugin-react'

const logger = createLogger()
const originalError = logger.error.bind(logger)
logger.error = (msg, options) => {
  const code = (options?.error as NodeJS.ErrnoException | undefined)?.code
  if (code === 'ECONNABORTED' || code === 'ECONNRESET') return
  originalError(msg, options)
}

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  customLogger: logger,
  plugins: [react()],
  server: {
    port: 3000,
    hmr: mode !== 'test',
    proxy: {
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8088',
        ws: true,
        changeOrigin: true,
      }
    }
  },
  build: {
    outDir: '../peegeeq-rest/src/main/resources/webroot',
    emptyOutDir: true,
    sourcemap: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-antd': ['antd', '@ant-design/icons'],
          'vendor-charts': ['recharts'],
          'vendor-utils': ['axios', 'zustand', 'dayjs'],
        }
      }
    }
  }
}))
