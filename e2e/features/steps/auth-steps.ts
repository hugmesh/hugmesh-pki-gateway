// Step bindings shared between auth/oauth-login-flow.feature and
// auth/per-role-access.feature.
//
// Steps fall into three buckets:
//   1. Setup: "Given the gateway is healthy", "Given a valid
//      Casdoor JWT for role <role>".
//   2. Triggers: "When the unauthenticated client GETs <path>",
//      "When the client GETs <path> with that JWT".
//   3. Assertions: "Then the response status is <int>", "Then the
//      response is a 302 redirect", "Then the Location header
//      points at Casdoor's authorize endpoint", etc.

import { expect } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { gatewayRequest, waitForGatewayHealthy } from './helpers/gateway';
import {
  readPreMintedToken,
  expectedCasdoorAuthorizeBaseUrl,
  expectedGatewayClientId,
} from './helpers/casdoor';

// ── Lifecycle ───────────────────────────────────────────────────────

Given('the gateway is healthy', async () => {
  await waitForGatewayHealthy();
});

// ── Token loading ───────────────────────────────────────────────────

Given('a valid Casdoor JWT for role {string}', async ({ world }, role: string) => {
  const token = readPreMintedToken(role);
  world.jwtsByRole.set(role, token);
});

// ── Unauthenticated GETs ────────────────────────────────────────────

When('the unauthenticated client GETs {string}', async ({ world }, path: string) => {
  const r = await gatewayRequest('GET', path);
  world.lastResponseStatus  = r.status;
  world.lastResponseHeaders = r.headers;
  world.lastLocationHeader  = r.location;
});

When('the unauthenticated client GETs the gated route {string}', async ({ world }, path: string) => {
  const r = await gatewayRequest('GET', path);
  world.lastResponseStatus  = r.status;
  world.lastResponseHeaders = r.headers;
  world.lastLocationHeader  = r.location;
});

// ── Bearer-authenticated GETs ───────────────────────────────────────

When('the client GETs {string} with that JWT', async ({ world }, path: string) => {
  // Pick the most-recently-loaded JWT — the scenarios load exactly
  // one role per scenario via the Given step.
  const token = [...world.jwtsByRole.values()].at(-1);
  if (!token) throw new Error('no JWT loaded; missing a "Given a valid Casdoor JWT…" step?');
  const r = await gatewayRequest('GET', path, { bearerToken: token });
  world.lastResponseStatus  = r.status;
  world.lastResponseHeaders = r.headers;
  world.lastLocationHeader  = r.location;
});

// ── Status assertions ──────────────────────────────────────────────

Then('the response status is {int}', async ({ world }, expected: number) => {
  expect(world.lastResponseStatus, 'last response status').toBe(expected);
});

Then('the response status is one of {int} or {int}',
     async ({ world }, a: number, b: number) => {
  expect([a, b], 'last response status').toContain(world.lastResponseStatus);
});

// ── Redirect assertions ────────────────────────────────────────────

Then('the response is a 302 redirect', async ({ world }) => {
  expect(world.lastResponseStatus, 'expected 302 redirect').toBe(302);
  expect(world.lastLocationHeader, 'Location header on 302').toBeTruthy();
});

Then("the Location header points at Casdoor's authorize endpoint", async ({ world }) => {
  const loc = world.lastLocationHeader ?? '';
  expect(loc, 'Location header').toContain(expectedCasdoorAuthorizeBaseUrl());
  // Casdoor's authorize endpoint is `/login/oauth/authorize`.
  expect(loc, 'Location authorize path').toMatch(/\/login\/oauth\/authorize/);
});

Then("the redirect carries the gateway's client_id query param", async ({ world }) => {
  const loc = world.lastLocationHeader ?? '';
  const url = new URL(loc);
  expect(url.searchParams.get('client_id'), 'client_id query param')
      .toBe(expectedGatewayClientId());
});

Then('the redirect carries a redirect_uri pointing back at the gateway',
     async ({ world }) => {
  const loc = world.lastLocationHeader ?? '';
  const url = new URL(loc);
  const redirectUri = url.searchParams.get('redirect_uri');
  expect(redirectUri, 'redirect_uri query param').toBeTruthy();
  // The redirect target is "<gateway base url>/login/oauth2/code/casdoor".
  expect(redirectUri!, 'redirect_uri callback path').toMatch(/\/login\/oauth2\/code\/casdoor$/);
});

Then('no Location header is present', async ({ world }) => {
  expect(world.lastLocationHeader, 'no Location header expected').toBeFalsy();
});
