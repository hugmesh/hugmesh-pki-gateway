// playwright-bdd World + AfterStep PNG hook. Mirrors the
// snapdragon.games and snapdragon-actions e2e harness shape so a
// reviewer reading across repos doesn't need to context-switch.

import { test as base, createBdd } from 'playwright-bdd';

interface World {
  lastResponseStatus?: number;
  lastLocationHeader?: string | null;
  lastResponseHeaders?: Record<string, string>;
  // Cached per-role bearer tokens, populated by the
  // `a valid Casdoor JWT for role` step. One Casdoor mint per
  // scenario; downstream steps reuse the same token.
  jwtsByRole: Map<string, string>;
}

export const test = base.extend<{ world: World }>({
  world: async ({}, use) => {
    await use({ jwtsByRole: new Map() });
  },
});

export const { Given, When, Then, Before, AfterStep } = createBdd(test);

let stepCounter = 0;
Before(async () => { stepCounter = 0; });

AfterStep(async ({ page, $step, $testInfo }) => {
  stepCounter += 1;
  try {
    const title = ($step as { title?: string })?.title ?? `step-${stepCounter}`;
    const safe = `${String(stepCounter).padStart(2, '0')}-${title}`
      .replace(/[^a-z0-9_-]+/gi, '-')
      .slice(0, 80);
    const path = $testInfo.outputPath(`${safe}.png`);
    await page.screenshot({ path, fullPage: false });
    await $testInfo.attach(`step ${safe}`, { path, contentType: 'image/png' });
  } catch { /* page closed mid-step */ }
});
