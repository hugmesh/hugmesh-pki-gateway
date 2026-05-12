# JWT resource-server validation — the gateway's Bearer-token side.
#
# A request that arrives with `Authorization: Bearer <jwt>` flows
# through the gateway's `oauth2ResourceServer(jwt(...))` chain. A
# valid signature + non-expired + Casdoor-issuer JWT clears the
# auth gate; everything else bounces.
Feature: JWT resource-server — the gateway as a Bearer-token validator
  As a programmatic client carrying a Casdoor-issued JWT
  I expect the gateway to validate signature + expiry + issuer
  Without redirecting me through the browser-side login flow.

  Background:
    Given the gateway is healthy

  Scenario: missing Authorization header on a gated route is 401-or-302
    When the unauthenticated client GETs "/actuator/gateway/routes"
    Then the response status is one of 401 or 302
    # 302 = the oauth2Login chain took precedence; 401 = the
    # resource-server chain rejected. Either is acceptable as
    # "anonymous can't read this"; deeper coverage in the per-role
    # feature file.

  Scenario: malformed Bearer token is rejected with 401
    When the client GETs "/actuator/gateway/routes" with Bearer "not-a-jwt"
    Then the response status is 401

  Scenario: expired JWT is rejected with 401
    When the client GETs "/actuator/gateway/routes" with an expired Casdoor JWT
    Then the response status is 401

  Scenario: a valid game-master JWT passes the resource-server check
    Given a valid Casdoor JWT for role "GAME-MASTER"
    When the client GETs "/actuator/gateway/routes" with that JWT
    Then the response status is 200
