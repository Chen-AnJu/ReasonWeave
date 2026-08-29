import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    exclude: ['e2e/**', 'e2e-real/**', 'node_modules/**', 'dist/**'],
  },
});
