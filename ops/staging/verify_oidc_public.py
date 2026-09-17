#!/usr/bin/env python3
"""Validate public OIDC discovery metadata and JWKS for staging evidence."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from typing import Any, Callable
from urllib.parse import urlparse


class ValidationError(ValueError):
    """Raised when public OIDC metadata violates the staging contract."""


def _require_https(url: str, label: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme.lower() != "https" or not parsed.netloc:
        raise ValidationError(f"{label} must use HTTPS")


def fetch_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "tiempojusto-staging-oidc-proof/1",
        },
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        payload = json.load(response)
    if not isinstance(payload, dict):
        raise ValidationError(f"{url} did not return a JSON object")
    return payload


def validate_metadata(
    discovery_url: str,
    expected_issuer: str,
    fetcher: Callable[[str], dict[str, Any]] = fetch_json,
) -> dict[str, Any]:
    _require_https(discovery_url, "discovery URL")
    _require_https(expected_issuer, "expected issuer")

    discovery = fetcher(discovery_url)
    if not isinstance(discovery, dict):
        raise ValidationError("discovery document must be a JSON object")

    issuer = discovery.get("issuer")
    if issuer != expected_issuer:
        raise ValidationError(
            f"issuer mismatch: expected {expected_issuer!r}, received {issuer!r}"
        )

    jwks_uri = discovery.get("jwks_uri")
    if not isinstance(jwks_uri, str) or not jwks_uri:
        raise ValidationError("discovery document is missing jwks_uri")
    _require_https(jwks_uri, "jwks_uri")

    jwks = fetcher(jwks_uri)
    if not isinstance(jwks, dict):
        raise ValidationError("JWKS document must be a JSON object")
    keys = jwks.get("keys")
    if not isinstance(keys, list) or not keys:
        raise ValidationError("JWKS must contain at least one key")

    return {
        "issuer": issuer,
        "jwks_uri": jwks_uri,
        "key_count": len(keys),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate public OIDC discovery metadata and JWKS for TiempoJusto staging."
    )
    parser.add_argument("discovery_url")
    parser.add_argument("expected_issuer")
    args = parser.parse_args(argv)

    try:
        proof = validate_metadata(args.discovery_url, args.expected_issuer)
    except (ValidationError, urllib.error.URLError, json.JSONDecodeError) as exc:
        print(f"OIDC public proof failed: {exc}", file=sys.stderr)
        return 1

    print(
        "OIDC public proof OK: "
        f"issuer={proof['issuer']} jwks_uri={proof['jwks_uri']} keys={proof['key_count']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
