// Step bindings unique to jwt/resource-server.feature. The
// generic status assertions live in auth-steps.ts.
//
// Three behaviours that need their own step shape:
//   1. "with Bearer <literal>"  — request carries a hand-built
//      Bearer string, used to assert malformed-token rejection.
//   2. "with an expired Casdoor JWT" — request carries a token
//      whose `exp` is in the past, used to assert the
//      resource-server rejects expired tokens.

import { When } from '../support/fixtures';
import { gatewayRequest } from './helpers/gateway';
import { readExpiredToken } from './helpers/casdoor';

When('the client GETs {string} with Bearer {string}',
     async ({ world }, path: string, literalToken: string) => {
  const r = await gatewayRequest('GET', path, { bearerToken: literalToken });
  world.lastResponseStatus  = r.status;
  world.lastResponseHeaders = r.headers;
  world.lastLocationHeader  = r.location;
});

When('the client GETs {string} with an expired Casdoor JWT',
     async ({ world }, path: string) => {
  const token = readExpiredToken();
  const r = await gatewayRequest('GET', path, { bearerToken: token });
  world.lastResponseStatus  = r.status;
  world.lastResponseHeaders = r.headers;
  world.lastLocationHeader  = r.location;
});
