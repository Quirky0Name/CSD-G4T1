import base64
import time

import httpx
import jwt
import pytest

from common.service_token import ServiceTokenAuth, decode_jwt_secret, mint_service_token

# the secret in Storage Management's application-test.yml / TestTokens.java
SM_TEST_SECRET = "c3RvcmFnZS10ZXN0LXNlY3JldC1ub3QtZm9yLXByb2Qh"


def test_token_has_the_shape_storage_management_expects():
    key = decode_jwt_secret(SM_TEST_SECRET)
    token = mint_service_token(key, "svc:updating")

    assert jwt.get_unverified_header(token)["alg"] == "HS256"
    claims = jwt.decode(token, key, algorithms=["HS256"])
    assert claims["sub"] == "svc:updating"
    assert claims["role"] == "service"
    assert 0 < claims["exp"] - time.time() <= 5 * 60


def test_short_secret_is_rejected():
    short = base64.b64encode(b"x" * 31).decode()
    with pytest.raises(ValueError, match="openssl rand -base64 32"):
        decode_jwt_secret(short)


def test_32_byte_secret_is_accepted():
    assert len(decode_jwt_secret(base64.b64encode(b"x" * 32).decode())) == 32


def test_non_base64_secret_is_rejected():
    with pytest.raises(ValueError, match="base64"):
        decode_jwt_secret("not base64 at all!")


def test_service_token_auth_signs_each_request_for_its_subject():
    key = decode_jwt_secret(SM_TEST_SECRET)
    request = httpx.Request("GET", "http://sm/internal/papers")

    signed = next(ServiceTokenAuth(key, "svc:research-evaluation").auth_flow(request))

    scheme, token = signed.headers["Authorization"].split(" ")
    claims = jwt.decode(token, key, algorithms=["HS256"])
    assert scheme == "Bearer"
    assert (claims["sub"], claims["role"]) == ("svc:research-evaluation", "service")
