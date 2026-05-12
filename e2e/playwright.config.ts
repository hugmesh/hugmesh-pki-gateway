import { defineConfig, devices } from '@playwright/test';
import { defineBddConfig } from 'playwright-bdd';

const bddTestDir = defineBddConfig({
  features: 'features/**/*.feature',
  steps: ['features/steps/**/*.ts', 'features/support/**/*.ts'],
});

export default defineConfig({
  // Single worker — the OAuth2-login scenario opens a real browser
  // session against Casdoor and parallel workers race the
  // SecurityContextRepository.
  fullyParallel: false,
  workers: 1,
  retries: 1,
  timeout: 180_000,

  reporter: [
    ['list'],
    ['html', { outputFolder: 'results/html', open: 'never' }],
  ],
  outputDir: 'results/artifacts',
  use: {
    // The gateway exposes the OAuth2 login flow + the actuator
    // health probe + DB-backed routes on this base URL. The bring-
    // up script forwards GATEWAY_URL when running.
    baseURL: process.env.GATEWAY_URL ?? 'http://localhost:8080',
    ignoreHTTPSErrors: true,
    // Everything ON so reviewers can replay a scenario without
    // re-running it. The AfterStep hook also attaches a PNG per
    // step transition.
    trace: 'retain-on-failure',
    screenshot: 'on',
    video: 'on',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'bdd',
      testDir: bddTestDir,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
