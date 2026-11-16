import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 중 /api 는 release-svc(8080)로 프록시. 운영에서는 nginx가 같은 역할(nginx.conf)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
})
