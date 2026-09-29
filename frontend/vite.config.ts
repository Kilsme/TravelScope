import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 5173 落在本机 Windows 保留端口区间 5141-5240（Hyper-V/WSL 动态保留，listen 报 EACCES），
    // 改用 3000；若系统重启后保留区间变化可改回
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
