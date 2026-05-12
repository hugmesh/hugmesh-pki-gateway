// HTTP helper for the id-gateway. The e2e suite cares about three
// behaviours of the gateway's own filter chain:
//
//   1. The public health probe — returns 200 to anyone.
//   2. The OAuth2 login redirect — anonymous request to a gated
//      route returns 302 with a Location header pointing at the
//      Casdoor authorize endpoint.
//   3. The JWT resource-server gate — Bearer-token requests are
//      validated against the configured issuer's JWKS.
//
// All three need to read the response without auto-following the
// redirect (so we can assert on the 302 + Location header).

import { request, APIResponse } from '@playwright/test';

export const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8080';

export interface GatewayResponse {
  status: number;
  headers: Record<string, string>;
  location: string | null;
}

/** Issue a single HTTP request against the gateway. Never follows
 *  redirects — the 302 is part of what the OAuth2 login scenarios
 *  assert against. */
export async function gatewayRequest(
  method: 'GET' | 'POST',
  path: string,
  options: { bearerToken?: string } = {},
): Promise<GatewayResponse> {
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    const headers: Record<string, string> = {};
    if (options.bearerToken) headers.Authorization = `Bearer ${options.bearerToken}`;
    const r: APIResponse = await api.fetch(`${GATEWAY_URL}${path}`, {
      method,
      headers,
      maxRedirects: 0,
    });
    return {
      status:   r.status(),
      headers:  r.headers(),
      location: r.headers()['location'] ?? null,
    };
  } finally {
    await api.dispose();
  }
}

/** Block until /actuator/health returns 200 OR the deadline lapses. */
export async function waitForGatewayHealthy(timeoutMs = 60_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await gatewayRequest('GET', '/actuator/health');
      if (r.status === 200) return;
    } catch { /* try again */ }
    await new Promise(res => setTimeout(res, 500));
  }
  throw new Error(`gateway not healthy after ${timeoutMs}ms at ${GATEWAY_URL}`);
}
