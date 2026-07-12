import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/admin': {
          target: env.VITE_GATESHIELD_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
        '/actuator': {
          target: env.VITE_GATESHIELD_API_BASE_URL || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      globals: true,
    },
  };
});
