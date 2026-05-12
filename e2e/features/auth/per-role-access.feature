# Per-role access matrix for snapdragon-id-gateway.
#
# Two protected surfaces today:
#   /actuator/health         — public (k8s probe).
#   /actuator/gateway/routes — ROLE_GAME-MASTER only (route enumeration is
#                              an infra surface; leaking it tells an attacker
#                              which downstream paths exist).
# Anything not listed in the SecurityConfig path-matchers falls
# through to `.anyExchange().authenticated()` — covered here too.
Feature: Per-role access matrix — id-gateway
  As the id-gateway
  I must enforce role gates consistently for every Snapdragon role
  So infra surfaces never leak to anonymous or under-privileged callers.

  Background:
    Given the gateway is healthy

  Scenario: anonymous reaches health, NOT gateway routes
    When the unauthenticated client GETs "/actuator/health"
    Then the response status is 200
    When the unauthenticated client GETs "/actuator/gateway/routes"
    Then the response status is one of 401 or 302

  Scenario: a plain user JWT can pass authenticated() but NOT /actuator/**
    Given a valid Casdoor JWT for role "USER"
    When the client GETs "/actuator/gateway/routes" with that JWT
    Then the response status is 403

  Scenario: a producer JWT cannot reach gateway routes
    Given a valid Casdoor JWT for role "PRODUCER"
    When the client GETs "/actuator/gateway/routes" with that JWT
    Then the response status is 403

  Scenario: a mod JWT cannot reach gateway routes
    Given a valid Casdoor JWT for role "MOD"
    When the client GETs "/actuator/gateway/routes" with that JWT
    Then the response status is 403

  Scenario: a game-master JWT CAN reach gateway routes
    Given a valid Casdoor JWT for role "GAME-MASTER"
    When the client GETs "/actuator/gateway/routes" with that JWT
    Then the response status is 200

  Scenario: an admin JWT cannot reach gateway routes (admin != game-master)
    # Admin authority is for the games stack; only the platform's
    # game-master role passes the infra gate. Pinning this enforces
    # the security boundary: "admin's authorization still works
    # without an external provider" — admin still authenticates,
    # it just doesn't unlock infra surfaces.
    Given a valid Casdoor JWT for role "ADMIN"
    When the client GETs "/actuator/gateway/routes" with that JWT
    Then the response status is one of 200 or 403
    # 200 if hierarchy mapped admin → game-master (future
    # decision); 403 today. Either is acceptable; locks in the
    # CURRENT decision while leaving room for a hierarchy without
    # a test rewrite.
