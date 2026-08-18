import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: { outDir: '../hearth-api/src/main/resources/static', emptyOutDir: true },
})
