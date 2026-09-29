import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const gateway = env.VITE_API_BASE_URL || 'http://localhost:8080';
  return {
    plugins: [react()],
    server: {
      host: 'localhost', port: 5173, strictPort: true,
      proxy: { '/api': { target: gateway, changeOrigin: true } },
    },
    preview: { host: 'localhost', port: 4173, strictPort: true },
  };
});
