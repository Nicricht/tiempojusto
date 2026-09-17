import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from verify_oidc_public import ValidationError, validate_metadata


class OidcPublicProofTests(unittest.TestCase):
    def test_accepts_https_discovery_exact_issuer_and_non_empty_jwks(self):
        responses = {
            "https://issuer.example/.well-known/openid-configuration": {
                "issuer": "https://issuer.example/",
                "jwks_uri": "https://issuer.example/.well-known/jwks.json",
            },
            "https://issuer.example/.well-known/jwks.json": {
                "keys": [{"kty": "RSA", "kid": "key-1"}],
            },
        }

        def fake_fetch(url: str):
            return responses[url]

        proof = validate_metadata(
            "https://issuer.example/.well-known/openid-configuration",
            "https://issuer.example/",
            fake_fetch,
        )

        self.assertEqual(proof["issuer"], "https://issuer.example/")
        self.assertEqual(proof["jwks_uri"], "https://issuer.example/.well-known/jwks.json")
        self.assertEqual(proof["key_count"], 1)

    def test_rejects_non_https_discovery_url(self):
        with self.assertRaisesRegex(ValidationError, "discovery URL must use HTTPS"):
            validate_metadata(
                "http://issuer.example/.well-known/openid-configuration",
                "https://issuer.example/",
                lambda _url: {},
            )

    def test_rejects_issuer_mismatch(self):
        def fake_fetch(_url: str):
            return {
                "issuer": "https://other.example/",
                "jwks_uri": "https://issuer.example/jwks",
            }

        with self.assertRaisesRegex(ValidationError, "issuer mismatch"):
            validate_metadata(
                "https://issuer.example/.well-known/openid-configuration",
                "https://issuer.example/",
                fake_fetch,
            )

    def test_rejects_non_https_jwks_uri(self):
        def fake_fetch(_url: str):
            return {
                "issuer": "https://issuer.example/",
                "jwks_uri": "http://issuer.example/jwks",
            }

        with self.assertRaisesRegex(ValidationError, "jwks_uri must use HTTPS"):
            validate_metadata(
                "https://issuer.example/.well-known/openid-configuration",
                "https://issuer.example/",
                fake_fetch,
            )

    def test_rejects_empty_jwks(self):
        responses = {
            "https://issuer.example/.well-known/openid-configuration": {
                "issuer": "https://issuer.example/",
                "jwks_uri": "https://issuer.example/jwks",
            },
            "https://issuer.example/jwks": {"keys": []},
        }

        with self.assertRaisesRegex(ValidationError, "JWKS must contain at least one key"):
            validate_metadata(
                "https://issuer.example/.well-known/openid-configuration",
                "https://issuer.example/",
                lambda url: responses[url],
            )


if __name__ == "__main__":
    unittest.main()
