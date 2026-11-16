import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 중 /api 는 release-svc(8080)로 프록시. 운영에서는 nginx가 같은 역할(nginx.conf)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // 핵심요약은 summary-svc(8082), 나머지 /api는 release-svc(8080). 운영 nginx.conf와 같은 규칙
      '^/api/releases/[^/]+/summary$': 'http://localhost:8082',
      '/api': 'http://localhost:8080',
    },
  },
})
