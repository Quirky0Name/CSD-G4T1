import pytest

from common.doi import normalize_doi


@pytest.mark.parametrize(
    "raw",
    [
        "10.1016/S0140-6736(20)31180-6",
        "https://doi.org/10.1016/S0140-6736(20)31180-6",
        "http://dx.doi.org/10.1016/S0140-6736(20)31180-6",
        "HTTPS://DOI.ORG/10.1016/S0140-6736(20)31180-6",
        "doi:10.1016/S0140-6736(20)31180-6",
        "DOI:10.1016/S0140-6736(20)31180-6",
        "  \t10.1016/S0140-6736(20)31180-6\n",
    ],
)
def test_normalises_to_lowercase_bare_doi(raw):
    assert normalize_doi(raw) == "10.1016/s0140-6736(20)31180-6"


@pytest.mark.parametrize("raw", [None, "", "   ", "doi:", "https://doi.org/"])
def test_empty_becomes_none(raw):
    assert normalize_doi(raw) is None


def test_matches_storage_management_quirks():
    # SM strips the prefix once and doesn't trim again afterwards
    assert normalize_doi("doi: 10.1/x") == " 10.1/x"
    assert normalize_doi("doi:doi:10.1/x") == "doi:10.1/x"
    # Java's trim() strips control characters as well as spaces
    assert normalize_doi("\x01 10.1/x \x1f") == "10.1/x"
