# OAuth2 Authorization Code flow — the gateway's `oauth2Login` side.
#
# The id-gateway is configured both as an OAuth2 client (initiates
# the login redirect via `oauth2Login` in SecurityConfig) AND as an
# OAuth2 resource server (validates Bearer JWTs). This feature
# exercises the CLIENT side: a browser session that hits a gated
# route should be bounced to Casdoor's authorize endpoint with the
# right query params.
#
# Per-step PNGs land via the AfterStep hook; full webm video covers
# the redirect chain.
Feature: OAuth2 login redirect — the gateway as an OAuth2 client
  As a logged-out browser
  I expect a gated route to bounce me to Casdoor's authorize endpoint
  Carrying the client_id + redirect_uri + scope + state params my
  callback handler needs to complete the Authorization Code flow.

  Background:
    Given the gateway is healthy

  Scenario: anonymous gated route triggers the login redirect
    When the unauthenticated client GETs the gated route "/api/example"
    Then the response is a 302 redirect
    And the Location header points at Casdoor's authorize endpoint
    And the redirect carries the gateway's client_id query param
    And the redirect carries a redirect_uri pointing back at the gateway

  Scenario: anonymous public health probe is NOT redirected
    When the unauthenticated client GETs "/actuator/health"
    Then the response status is 200
    And no Location header is present

  Scenario: hitting /login/oauth2/authorization/casdoor manually redirects too
    When the unauthenticated client GETs "/login/oauth2/authorization/casdoor"
    Then the response is a 302 redirect
    And the Location header points at Casdoor's authorize endpoint
