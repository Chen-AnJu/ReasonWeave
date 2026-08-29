import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const apiProxyTarget = process.env.RW_API_PROXY_TARGET ?? 'http://127.0.0.1:18080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: false,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query'],
          'form-vendor': ['react-hook-form', '@hookform/resolvers', 'zod', 'ajv', 'ajv-formats'],
          'graph-vendor': ['@xyflow/react', 'html-to-image'],
          icons: ['lucide-react'],
        },
      },
    },
  },
});
