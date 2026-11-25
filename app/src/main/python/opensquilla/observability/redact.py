"""Structured and text scrubbing for user-shareable diagnostic artifacts.

JSON objects pass through :func:`scrub_json` before serialization to preserve
metadata types and JSON syntax. Free text uses :func:`scrub_text` to mask
credential assignments, headers and recognizable token values.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

_REDACTED = "[redacted]"

# key=value / key: value / "key": "value" where the key looks secret-shaped.
# Match complete credential suffixes using component or namespace boundaries.
# Prefixes may contain punctuation or Unicode in custom config/header
# names; restricting them to ASCII would weaken existing config redaction.
# No blanket `_key` suffix: benign identifiers like `session_key` must stay
# readable in diagnostics.
# Known compound credentials must also match without camel-case boundaries:
# HTTP header casing alone must not change whether an auth token is masked.
_SECRET_KEY_END = (
    r"(?:api_?key|token|secret_?access_?key|secret_?key|secret|password"
    r"|authorization|signing[_-]?secret|private[_-]?key"
    r"|app[_-]?secret|verification[_-]?token|encrypt[_-]?key|encoding[_-]?aes[_-]?key"
    r"|(?:api|auth|access|refresh|id|bearer|app)_?token|client_?secret"
    r"|corp_?secret)\Z"
)
_SECRET_KEY_RE = re.compile(r"(?:^|[._])" + _SECRET_KEY_END, re.IGNORECASE)
_SECRET_SUFFIX_RE = re.compile(_SECRET_KEY_END, re.IGNORECASE)
_CAMEL_BOUNDARY_RE = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_NAMESPACE_PUNCTUATION_RE = re.compile(r"[^\w\s.-]")


def _is_secret_key(key: str) -> bool:
    # Custom header/config names can use punctuation such as +, ! and $ as
    # namespace separators. Treat these like dots, retaining Unicode letters
    # and the existing hyphen/underscore compound-word boundaries.
    key = _NAMESPACE_PUNCTUATION_RE.sub(".", key)
    normalized = _CAMEL_BOUNDARY_RE.sub("_", key).replace("-", "_").lower()
    # Also retain literal case-insensitive spellings: unusual casing such as
    # aPiKeY must not turn one recognized credential name into unrelated words.
    literal = key.replace("-", "_").lower()
    # Capability predicates describe credentials without containing them.
    # Terminal metadata (apiKeyEnv, apiKeyConfigured, tokenCount) fails the
    # anchored suffix match below, as do unrelated bare words ending in "secret".
    # Only the final dotted component identifies a namespaced predicate;
    # interior words in credentials such as service_has_token do not.
    field = literal.rsplit(".", 1)[-1]
    # Predicate separators must be present in the original spelling: camel
    # splitting can manufacture "has_" from hasH_token or "is_" from isLand_token.
    # Compact predicates instead require a complete known credential remainder.
    predicates = ("requires", "has", "is", "supports")
    if any(
        field.startswith(f"{predicate}_") or (
            field.startswith(predicate)
            and _SECRET_KEY_RE.fullmatch(field[len(predicate):]) is not None
        )
        for predicate in predicates
    ):
        return False
    if (
        _SECRET_KEY_RE.search(normalized) is not None
        or _SECRET_KEY_RE.search(literal) is not None
    ):
        return True
    # A real namespace can qualify arbitrary compound credentials (for example,
    # x-securitytoken). Do not depend on their original casing or enumerate
    # vendor names. The bounded suffix pattern has no greedy prefix, so
    # scanning the field (including internal separators) remains linear.
    # Ambiguous namespaced credential suffixes are conservatively masked; bare
    # ordinary words still need a credential boundary or a known alias above.
    separator = max(literal.rfind("."), literal.rfind("_"))
    return separator > 0 and _SECRET_SUFFIX_RE.search(field) is not None


# Common Authorization credential schemes; the scheme word plus its payload is
# masked as one value (Basic base64, opaque Token blobs, Digest params, ...).
_AUTH_SCHEME = r"(?:bearer|basic|token|digest)"
# Scan assignment prefixes separately from their values. A benign outer field
# (message="api_key=...") must not consume the nested secret assignment. A
# matched credential value is consumed once, without recursive text scrubbing.
# The left boundary prevents retrying an identifier at each character, keeping
# long unbroken log runs linear. Consume optional CLI dashes before classifying
# the complete key, so flags remain reachable without matching inside names.
# Retain HTTP field-name punctuation so a compound credential keeps its
# namespace. Quoted keys close with their opening quote; a lazy key match lets
# an apostrophe remain either an internal header character or a closing quote.
_ASSIGNMENT_KEY_CHAR = r"[\w.!#$%&'*+^`|~-]"
_ASSIGNMENT_RE = re.compile(
    rf"""(?ix)
    (?<!{_ASSIGNMENT_KEY_CHAR})
    (?P<key_quote>["'])?(?:--?)?(?P<key>{_ASSIGNMENT_KEY_CHAR}+?)
    (?(key_quote)(?P=key_quote)|["']?)[ \t]*[=:][ \t]*
    """,
)
# Notes on value shape:
# - Separators use [ \t]* (never \s*) so a bare trailing label like
#   "password:\n" cannot swallow the first word of the next line.
# - <quote> is an *optional group* (not a group matching an optional char) so
#   the (?(quote)...) conditional can pick the quote-aware branch: a quoted
#   value runs to the closing quote or newline, spaces included.
# - The value alternation matches the [redacted] sentinel wholly first, making
#   scrubbing idempotent (re-scrubbing an already-scrubbed artifact is a no-op
#   instead of stacking stray "]" characters).
_ASSIGNMENT_VALUE_RE = re.compile(
    rf"""(?ix)
    (?P<quote>["'])?
    (?P<value>
        \[redacted\](?![^"'\s,}}\]])
        |(?(quote)(?:\\[^\r\n]|(?!(?P=quote))[^\\\r\n])+|
            (?:{_AUTH_SCHEME}[ \t]+)?[^"'\s,}}\]]+)
    )
    """,
)
# Bare bearer tokens outside key/value form. The class includes +/= so base64
# payloads are masked in full (over-masking a trailing "=" is fine; leaking a
# token suffix is not).
_BEARER_RE = re.compile(r"(?i)(?P<prefix>bearer\s+)(?P<value>[a-z0-9._\-+/=]+)")
# Bare provider/service tokens with globally distinctive prefixes. Provider and
# channel errors echo credentials verbatim with no key=value structure around
# them ("Incorrect API key sk-... provided"), and those messages flow straight
# into turn_errors and the public diagnostics bundle. Every branch is anchored
# to word-run boundaries and length-floored so ordinary prose ("skill",
# "risk-free", "eyJustSaying") never matches; over-masking token-shaped strings
# is fine, leaking a token tail is not. The literal prefixes keep scanning
# linear on megabyte log tails (each attempt fails on the first character).
_RUN = r"[A-Za-z0-9_-]"
_BARE_TOKEN_RE = re.compile(
    rf"""(?x)
    (?<!{_RUN})
    (?:
        sk-{_RUN}{{16,}}                          # OpenAI-style (incl. sk-proj-, sk-ant-)
        |sk_tr_{_RUN}{{16,}}                      # TokenRhythm keys (underscore, not hyphen)
        |xox[abposr]-[A-Za-z0-9-]{{10,}}          # Slack bot/user/app/legacy tokens
        |gh[pousr]_[A-Za-z0-9_]{{16,}}            # GitHub classic tokens (ghp/gho/ghu/ghs/ghr)
        |github_pat_[A-Za-z0-9_]{{16,}}           # GitHub fine-grained PATs
        |AKIA[0-9A-Z]{{16}}                       # AWS access key id
        |eyJ{_RUN}{{5,}}(?:\.{_RUN}{{8,}}){{2,}}  # JWT-shaped dotted base64url runs
        |AIza[0-9A-Za-z_-]{{35}}                  # Google API keys
    )
    (?!{_RUN})
    """
)
# Slack incoming-webhook URLs carry the credential in the path; keep the host
# recognizable and mask only the path. The path class excludes "[" so an
# already-masked "services/[redacted]" cannot rematch (idempotent).
_SLACK_WEBHOOK_RE = re.compile(
    r"(?P<prefix>\bhooks\.slack\.com/services/)[A-Za-z0-9/_-]+"
)


def scrub_text(text: str) -> str:
    """Mask secret-shaped values and normalize the home directory to ``~``."""
    parts: list[str] = []
    cursor = search_from = 0
    while assignment := _ASSIGNMENT_RE.search(text, search_from):
        search_from = assignment.end()
        if not _is_secret_key(assignment.group("key")):
            continue
        value = _ASSIGNMENT_VALUE_RE.match(text, search_from)
        if value is None:
            continue
        parts.extend((text[cursor:value.start("value")], _REDACTED))
        cursor = search_from = value.end()
    parts.append(text[cursor:])
    scrubbed = "".join(parts)
    scrubbed = _BEARER_RE.sub(lambda m: f"{m.group('prefix')}{_REDACTED}", scrubbed)
    scrubbed = _BARE_TOKEN_RE.sub(_REDACTED, scrubbed)
    scrubbed = _SLACK_WEBHOOK_RE.sub(lambda m: f"{m.group('prefix')}{_REDACTED}", scrubbed)
    home = str(Path.home())
    if home and home != "/":
        scrubbed = scrubbed.replace(home, "~")
    return scrubbed


def scrub_json(value: Any) -> Any:
    """Copy JSON data, masking secret fields and scrubbing only string leaves.

    Benign booleans, numbers and null retain their types. A credential field
    is masked as a whole even when its value is numeric or a container. This
    must run before serialization; text scrubbing is not a JSON transformation.
    Unknown values retain the bundle's historical string conversion, with the
    resulting string scrubbed too.
    """
    if isinstance(value, dict):
        return {
            scrub_text(key) if isinstance(key, str) else key: (
                _REDACTED if isinstance(key, str) and _is_secret_key(key) else scrub_json(item)
            )
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [scrub_json(item) for item in value]
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return scrub_text(value if isinstance(value, str) else str(value))
