import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  // Vite must not import local application settings into the test runner.
  envDir: false,
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    // Prisma can independently load .env when the client is constructed.
    env: {
      DATABASE_URL: process.env.PI_AGENT_TEST_DATABASE_URL?.trim() || 'postgresql://unit:unit@127.0.0.1:1/signalforge_unit',
      SIGNALFORGE_AUTH_MODE: 'disabled',
      SIGNALFORGE_LLM_AGENT_ENABLED: '1',
    },
    include: ['src/**/*.test.ts'],
    clearMocks: true,
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      reportsDirectory: 'coverage/vitest',
      reporter: ['text', 'json-summary', 'html'],
      include: ['src/lib/**/*.ts', 'src/app/api/**/route.ts'],
      exclude: ['src/**/*.test.ts', 'src/**/*.d.ts'],
      thresholds: {
        lines: 51,
        functions: 52,
        branches: 42,
        statements: 50,
      },
    },
  },
});
