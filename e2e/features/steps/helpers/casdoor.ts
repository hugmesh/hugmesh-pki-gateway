// Casdoor admin-API + token-mint helpers for the id-gateway e2e
// harness.
//
// Two distinct concerns:
//
//   1. Role membership — `ensureRoleMember` arranges the
//      e2e seed user into a given role so the gateway's JWT
//      authorities converter will read `ROLE_<NAME>` off the issued
//      token. Idempotent so the same scenario can re-run.
//
//   2. Bearer-token minting — `mintToken(role)` returns a JWT
//      validatable against the gateway's resource-server config.
//      The bring-up script writes each role's bearer to
//      `e2e/.tokens.json`; the helper reads from there first, then
//      falls back to TOKEN_<ROLE> env vars (CI / hand-driven dev).

import { request } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

interface CasdoorConfig {
  baseUrl: string;
  adminClientId: string;
  adminClientSecret: string;
  organization: string;
}

function cfg(): CasdoorConfig {
  return {
    baseUrl:           process.env.CASDOOR_URL           ?? 'http://localhost:8000',
    adminClientId:     process.env.CASDOOR_CLIENT_ID     ?? 'snapdragon-id-gateway',
    adminClientSecret: process.env.CASDOOR_CLIENT_SECRET ?? '',
    organization:      process.env.CASDOOR_ORG           ?? 'snapdragon-systems',
  };
}

function basicAuth(): string {
  const c = cfg();
  return 'Basic ' + Buffer.from(`${c.adminClientId}:${c.adminClientSecret}`).toString('base64');
}

/** Idempotent role-membership upsert. */
export async function ensureRoleMember(roleName: string, username: string): Promise<void> {
  const c = cfg();
  const api = await request.newContext();
  try {
    const memberId = `${c.organization}/${username}`;
    const r = await api.get(
      `${c.baseUrl}/api/get-role?id=${c.organization}/${roleName}`,
      { headers: { Authorization: basicAuth() } });
    const body = await r.json().catch(() => ({}));
    const current: string[] = body?.data?.users ?? [];
    if (current.includes(memberId)) return;
    await api.post(
      `${c.baseUrl}/api/update-role?id=${c.organization}/${roleName}`,
      {
        headers: { Authorization: basicAuth(), 'Content-Type': 'application/json' },
        data:    { owner: c.organization, name: roleName, users: [...current, memberId] },
      });
  } finally {
    await api.dispose();
  }
}

/** Read a pre-minted token for the named role. The bring-up script
 *  writes one entry per role to `.tokens.json` after the gateway +
 *  Casdoor are healthy. CI / dev can override via
 *  TOKEN_<ROLE_UPPER_UNDERSCORE>. */
export function readPreMintedToken(role: string): string {
  const tokensFile = path.join(process.cwd(), '.tokens.json');
  if (fs.existsSync(tokensFile)) {
    const parsed = JSON.parse(fs.readFileSync(tokensFile, 'utf8'));
    if (parsed[role]) return parsed[role] as string;
  }
  const envKey = `TOKEN_${role.replace(/-/g, '_').toUpperCase()}`;
  const fromEnv = process.env[envKey];
  if (fromEnv) return fromEnv;
  throw new Error(`No bearer token for role ${role}; expected $${envKey} or e2e/.tokens.json`);
}

/** A pre-built expired JWT, base64url-encoded. The bring-up script
 *  writes this to `.tokens.json` as `__expired` when minting per-
 *  role tokens — it's just any role's token with `exp` set to one
 *  second ago. Env-var override: TOKEN_EXPIRED. */
export function readExpiredToken(): string {
  const tokensFile = path.join(process.cwd(), '.tokens.json');
  if (fs.existsSync(tokensFile)) {
    const parsed = JSON.parse(fs.readFileSync(tokensFile, 'utf8'));
    if (parsed.__expired) return parsed.__expired as string;
  }
  const fromEnv = process.env.TOKEN_EXPIRED;
  if (fromEnv) return fromEnv;
  throw new Error('No expired token; expected $TOKEN_EXPIRED or "__expired" in .tokens.json');
}

/** The configured Casdoor base URL the OAuth2 login redirect should
 *  point at — used by the OAuth2 login feature's Location assertion. */
export function expectedCasdoorAuthorizeBaseUrl(): string {
  return cfg().baseUrl;
}

/** The id-gateway's own client-id with Casdoor — used to assert the
 *  redirect carries the right `client_id` query param. */
export function expectedGatewayClientId(): string {
  return cfg().adminClientId;
}
