import { defineConfig, devices } from '@playwright/test';

delete process.env.NO_COLOR;

export default defineConfig({
  testDir: './e2e-real',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  reporter: [
    ['line'],
    ['html', { open: 'never' }],
  ],
  use: {
    baseURL: 'http://127.0.0.1:4174',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'pnpm exec vite preview --port 4174',
    url: 'http://127.0.0.1:4174',
    reuseExistingServer: false,
    env: {
      RW_API_PROXY_TARGET: 'http://127.0.0.1:18081',
    },
  },
  projects: [
    {
      name: 'chromium-real-stack',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1024 } },
    },
  ],
});
