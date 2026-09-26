import logging

import httpx
import respx
from pydantic import SecretStr

from common.doi import Doi
from updating.main import configure_logging
from updating.sources import SourceStatus, fetch_openalex

KEY = "s3cret-openalex-key"


async def call_openalex_and_fail() -> None:
    with respx.mock:
        respx.get(url__startswith="https://api.openalex.org/works/").respond(500)
        async with httpx.AsyncClient() as http:
            result = await fetch_openalex(http, Doi("10.1/x"), SecretStr(KEY))
    assert result is SourceStatus.ERROR


async def test_the_openalex_key_never_reaches_the_logs(caplog):
    httpx_logger = logging.getLogger("httpx")
    original = httpx_logger.level
    caplog.set_level(logging.DEBUG)
    try:
        # control: at INFO, httpx itself logs the full URL, key included
        httpx_logger.setLevel(logging.INFO)
        await call_openalex_and_fail()
        assert KEY in caplog.text

        caplog.clear()
        configure_logging()
        await call_openalex_and_fail()
        assert KEY not in caplog.text
        assert "openalex returned 500" in caplog.text  # our own log line is still there
    finally:
        httpx_logger.setLevel(original)
