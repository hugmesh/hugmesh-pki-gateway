#!/usr/bin/env python3
"""Fail the build when the `gateway_routes` Liquibase seed drifts from application-pki.yml.

The seed rows in `liquibase/changelog/` are plain literals: Liquibase XML has no
equivalent of Spring's `${VAR:default}` relaxed-binding syntax, so until now the
two files could only be kept in agreement by hand (see the changeSet comment in
`001-gateway-routes.xml` and the README's "Known Gaps / TODO"). This script is
the automated half of that promise: it resolves each YAML route's
`${VAR:default}` down to its DEFAULT and compares route id, uri and predicates —
in order — against the effective `gateway_routes` rows the changelogs seed.

Only the DEFAULTS are comparable. A `CASDOOR_URI` / `HEADSCALE_URI` override
applied at deploy time still does not reach the seeded fallback rows; that is
the limitation this check BRACKETS (it stops the two files' defaults from
diverging unnoticed), not one it removes.

Stdlib only — no PyYAML — so CI can run it in a bare `python` image with no
install step. The YAML reader is deliberately a minimal block-mapping /
block-sequence scanner rather than a general parser: it understands exactly the
shape `application-pki.yml` uses and raises on anything it does not, so it can
never pass by silently reading nothing.

Exit codes:
  0  the two files agree
  1  DRIFT — they disagree (the build should fail)
  2  the check could not run: a parse failure, or a changelog construct this
     script does not model. Never exits 0 on a file it did not understand.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
YAML_FILE = REPO_ROOT / "application-pki.yml"
MASTER_CHANGELOG = REPO_ROOT / "liquibase" / "changelog" / "db.changelog-master.xml"

# Where the routes live in application-pki.yml. SCG 5.0 (Boot 4) moved static
# route config here from the legacy `spring.cloud.gateway.routes` prefix; if a
# future edit ever moves it back, this script fails with exit 2 rather than
# comparing nothing (the legacy prefix binds zero routes at runtime anyway).
ROUTES_KEY_PATH = ("spring", "cloud", "gateway", "server", "webflux", "routes")

SEED_TABLE = "gateway_routes"
DBCHANGELOG_NS = "http://www.liquibase.org/xml/ns/dbchangelog"

# The `predicates` column is a single string while YAML carries a list. Every
# route in this repo (and in the generator Application it mirrors) has exactly
# one predicate, so the separator is a convention, not something the shared
# gateway image pins here. Comparing against the wrong separator can only ever
# produce a LOUD false failure naming both sides, never a silent pass.
PREDICATE_SEPARATOR = ","

_KEY_RE = re.compile(r"^([A-Za-z0-9_.-]+):(?:\s+(.*))?$")
_PLACEHOLDER_RE = re.compile(r"^\$\{([A-Za-z0-9_.-]+)(?::(.*))?\}$")
_WHERE_ROUTE_ID_RE = re.compile(r"route_id\s*=\s*'([^']*)'")


class CheckError(Exception):
    """The check cannot be performed (exit 2) — as opposed to drift (exit 1)."""


# --------------------------------------------------------------------------
# application-pki.yml
# --------------------------------------------------------------------------

def _decomment(line):
    """Drop a trailing `#` comment (YAML requires it at line start or after space)."""
    for i, ch in enumerate(line):
        if ch == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i].rstrip()
    return line.rstrip()


def _unquote(value):
    v = value.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        return v[1:-1]
    return v


def _assign(route, text, where):
    """Apply one `key: value` (or `key:` opening a nested sequence) to a route.

    Returns the key when it opened a nested sequence, else None.
    """
    m = _KEY_RE.match(text)
    if m is None:
        raise CheckError("%s: cannot parse route entry %r" % (where, text))
    key, value = m.group(1), (m.group(2) or "").strip()
    if value == "":
        route[key] = []
        return key
    route[key] = _unquote(value)
    return None


def _find_routes_block(lines):
    """Return (first line index after `routes:`, that key's indent)."""
    stack = []  # [(indent, key)] — the mapping path down to the current line
    for idx, raw in enumerate(lines):
        line = _decomment(raw)
        if not line.strip():
            continue
        stripped = line.lstrip()
        if stripped.startswith("-"):
            continue  # sequence entry, never part of the mapping path
        m = _KEY_RE.match(stripped)
        if m is None:
            continue
        indent = len(line) - len(stripped)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, m.group(1)))
        if tuple(k for _, k in stack) == ROUTES_KEY_PATH:
            if (m.group(2) or "").strip():
                raise CheckError(
                    "%s:%d: %s is not a block sequence"
                    % (YAML_FILE.name, idx + 1, ".".join(ROUTES_KEY_PATH))
                )
            return idx + 1, indent
    raise CheckError(
        "%s: no %s block found - nothing to compare the seed against"
        % (YAML_FILE.name, ".".join(ROUTES_KEY_PATH))
    )


def _parse_route_items(lines, start, routes_indent):
    routes = []
    item_indent = None
    current = None
    seq_key = None
    for offset, raw in enumerate(lines[start:]):
        line = _decomment(raw)
        if not line.strip():
            continue
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        where = "%s:%d" % (YAML_FILE.name, start + offset + 1)
        if not stripped.startswith("-") and indent <= routes_indent:
            break  # dedented back out of the routes: block
        if stripped.startswith("-"):
            rest = stripped[1:].strip()
            if item_indent is None:
                item_indent = indent
            if indent < item_indent:
                break  # a sequence outside (shallower than) the routes: block
            if indent == item_indent:
                current = {}
                routes.append(current)
                seq_key = _assign(current, rest, where) if rest else None
                continue
            if current is None or seq_key is None:
                raise CheckError("%s: unexpected sequence entry %r" % (where, rest))
            current[seq_key].append(_unquote(rest))
            continue
        if current is None:
            raise CheckError("%s: expected a route entry, got %r" % (where, stripped))
        seq_key = _assign(current, stripped, where)
    return routes


def resolve_default(value, where):
    """Reduce a `${VAR:default}` placeholder to the default a plain literal must match."""
    if "${" not in value:
        return value
    m = _PLACEHOLDER_RE.match(value)
    if m is None:
        raise CheckError(
            "%s: %r embeds a placeholder in a form this check does not model "
            "(only a whole-value ${VAR:default} is comparable)" % (where, value)
        )
    name, default = m.group(1), m.group(2)
    if not default:
        raise CheckError(
            "%s: ${%s} declares no default, so there is no literal for the "
            "Liquibase seed to be checked against" % (where, name)
        )
    return default


def parse_yaml_routes(path):
    """[(route_id, uri-default, [predicate, ...]), ...] in declaration order."""
    lines = path.read_text(encoding="utf-8").splitlines()
    start, routes_indent = _find_routes_block(lines)
    items = _parse_route_items(lines, start, routes_indent)
    if not items:
        raise CheckError("%s: %s is empty" % (path.name, ".".join(ROUTES_KEY_PATH)))
    routes = []
    for pos, item in enumerate(items):
        where = "%s route #%d" % (path.name, pos + 1)
        for key in ("id", "uri", "predicates"):
            if key not in item:
                raise CheckError("%s: missing %r" % (where, key))
        if not isinstance(item["predicates"], list) or not item["predicates"]:
            raise CheckError("%s: predicates is not a non-empty sequence" % where)
        routes.append((item["id"], resolve_default(item["uri"], where), item["predicates"]))
    return routes


# --------------------------------------------------------------------------
# liquibase/changelog
# --------------------------------------------------------------------------

def _q(tag):
    return "{%s}%s" % (DBCHANGELOG_NS, tag)


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _columns(change, where):
    values = {}
    for column in change.findall(_q("column")):
        name = column.get("name")
        if name is None:
            raise CheckError("%s: <column> without a name" % where)
        if column.get("value") is None:
            raise CheckError(
                "%s: column %r carries no plain `value` - valueNumeric / "
                "valueComputed and friends are not modelled by this check"
                % (where, name)
            )
        values[name] = column.get("value")
    return values


def _apply_changelog(path, rows, seen):
    """Fold one changelog (and anything it <include>s) into the effective rows."""
    if not path.is_file():
        raise CheckError("included changelog %s does not exist" % path)
    resolved = path.resolve()
    if resolved in seen:
        raise CheckError("changelog %s is included in a cycle" % path.name)
    seen.add(resolved)
    root = ET.parse(path).getroot()
    # Document order IS applied order, and an <include> can appear anywhere
    # among the changeSets, so walk the children rather than picking out
    # changeSets first — otherwise a nested include would be skipped silently.
    for child in root:
        tag = _local(child.tag)
        if tag == "include":
            name = child.get("file")
            if not name:
                raise CheckError("%s: <include> without a file attribute" % path.name)
            _apply_changelog(path.parent / name, rows, seen)
            continue
        if tag == "includeAll":
            raise CheckError(
                "%s: <includeAll> is not modelled by this check - list the "
                "changelogs with <include> or teach this script" % path.name
            )
        if tag != "changeSet":
            continue
        change_set = child
        set_id = change_set.get("id", "?")
        # Direct children only: this deliberately never descends into
        # <rollback>, whose changes are the UNDO and are not applied.
        for change in change_set:
            change_tag = _local(change.tag)
            if change_tag in ("preConditions", "rollback", "comment", "validCheckSum"):
                continue
            where = "%s changeSet '%s' <%s>" % (path.name, set_id, change_tag)
            table = change.get("tableName")
            if table is not None and table != SEED_TABLE:
                continue  # some other table — not this check's business
            if change_tag == "insert":
                columns = _columns(change, where)
                route_id = columns.get("route_id")
                if not route_id:
                    raise CheckError("%s: insert without a route_id column" % where)
                if route_id in rows:
                    raise CheckError("%s: route_id %r is seeded twice" % (where, route_id))
                for key in ("uri", "predicates"):
                    if key not in columns:
                        raise CheckError("%s: insert without a %s column" % (where, key))
                rows[route_id] = {
                    "uri": columns["uri"],
                    "predicates": columns["predicates"],
                    "where": where,
                }
            elif change_tag == "update":
                clause = (change.findtext(_q("where")) or "").strip()
                match = _WHERE_ROUTE_ID_RE.search(clause)
                if not match:
                    raise CheckError(
                        "%s: <where> does not pin a single route_id - this check "
                        "only models per-route updates" % where
                    )
                route_id = match.group(1)
                if route_id not in rows:
                    raise CheckError(
                        "%s: updates route_id %r, which no earlier changelog seeds"
                        % (where, route_id)
                    )
                # Preconditions and extra <where> guards are NOT simulated: this
                # compares the state the repo's own changelogs describe, not the
                # state of any particular live database.
                rows[route_id].update(_columns(change, where))
                rows[route_id]["where"] = where
            else:
                raise CheckError(
                    "%s: this change is not modelled by this check - extend "
                    "%s before landing it" % (where, pathlib.Path(__file__).name)
                )


def parse_seed_routes(master):
    """{route_id: {"uri", "predicates", "where"}} — the effective seeded table."""
    rows = {}  # insertion-ordered (dicts preserve order), = changelog order
    _apply_changelog(master, rows, set())
    if not rows:
        raise CheckError("%s: the changelogs seed no %s row" % (master.name, SEED_TABLE))
    return rows


# --------------------------------------------------------------------------

def compare(yaml_routes, seed_rows):
    problems = []
    yaml_by_id = {r[0]: r for r in yaml_routes}
    yaml_ids = [r[0] for r in yaml_routes]
    seed_ids = list(seed_rows)

    if len(yaml_by_id) != len(yaml_ids):
        raise CheckError("%s declares a duplicate route id" % YAML_FILE.name)

    for route_id in yaml_ids:
        if route_id not in seed_rows:
            problems.append(
                "route %r is configured in %s but no %s row seeds it"
                % (route_id, YAML_FILE.name, SEED_TABLE)
            )
    for route_id in seed_ids:
        if route_id not in yaml_by_id:
            problems.append(
                "%s row %r (%s) has no route in %s"
                % (SEED_TABLE, route_id, seed_rows[route_id]["where"], YAML_FILE.name)
            )
    if not problems and yaml_ids != seed_ids:
        # Route order decides which predicate wins a request, so it is drift too.
        problems.append(
            "route order differs: %s has %s, the seed has %s"
            % (YAML_FILE.name, yaml_ids, seed_ids)
        )

    for route_id in yaml_ids:
        row = seed_rows.get(route_id)
        if row is None:
            continue
        _, uri, predicates = yaml_by_id[route_id]
        if row["uri"] != uri:
            problems.append(
                "route %r uri: %s default is %r but the seed row is %r"
                % (route_id, YAML_FILE.name, uri, row["uri"])
            )
        seeded = [p.strip() for p in row["predicates"].split(PREDICATE_SEPARATOR)]
        if seeded != predicates:
            problems.append(
                "route %r predicates: %s has %r but the seed row is %r"
                % (route_id, YAML_FILE.name, predicates, seeded)
            )
    return problems


def main():
    try:
        yaml_routes = parse_yaml_routes(YAML_FILE)
        seed_rows = parse_seed_routes(MASTER_CHANGELOG)
        problems = compare(yaml_routes, seed_rows)
    except CheckError as exc:
        sys.stderr.write("route-seed drift check could not run: %s\n" % exc)
        return 2
    except ET.ParseError as exc:
        sys.stderr.write("route-seed drift check could not run: bad XML: %s\n" % exc)
        return 2

    if problems:
        sys.stderr.write(
            "%s has drifted from the %s Liquibase seed:\n" % (YAML_FILE.name, SEED_TABLE)
        )
        for problem in problems:
            sys.stderr.write("  - %s\n" % problem)
        sys.stderr.write(
            "Both files are hand-maintained mirrors of the generator's ArgoCD "
            "Application (README \"Source of truth\"); fix whichever one is wrong.\n"
        )
        return 1

    print(
        "route-seed drift check OK: %d route(s) agree between %s and the %s seed"
        % (len(yaml_routes), YAML_FILE.name, SEED_TABLE)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
