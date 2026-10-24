import pytest
from pydantic import ValidationError

from research_evaluation.investigation.links import Link, LinkSource, text_links, unique


def test_a_link_has_exactly_one_of_doi_and_url():
    Link(doi="10.1/x", url=None, role=None, source=LinkSource.CROSSREF)
    Link(doi=None, url="https://example.org", role=None, source=LinkSource.CROSSREF)
    with pytest.raises(ValidationError):
        Link(doi="10.1/x", url="https://example.org", role=None, source=LinkSource.CROSSREF)
    with pytest.raises(ValidationError):
        Link(doi=None, url=None, role="updated-by", source=LinkSource.CROSSREF)


def test_a_link_is_frozen_and_dumps_all_four_fields():
    link = Link(doi="10.1/x", url=None, role="update-to", source=LinkSource.CROSSREF)

    with pytest.raises(ValidationError):
        link.role = "updated-by"
    assert link.model_dump(mode="json") == {
        "doi": "10.1/x",
        "url": None,
        "role": "update-to",
        "source": "crossref",
    }


def test_dois_in_text_come_back_normalised_and_stripped():
    text = (
        "This retracts Smith et al. (doi: 10.1371/journal.PONE.0258361). "
        "See also 10.1016/S0140-6736(20)31180-6, and 10.1093/evolut/qpad094;"
    )

    assert [link.doi for link in text_links(text)] == [
        "10.1371/journal.pone.0258361",
        "10.1016/s0140-6736(20)31180-6",
        "10.1093/evolut/qpad094",
    ]
    assert all(
        link.role is None and link.url is None and link.source is LinkSource.EUROPEPMC_TEXT
        for link in text_links(text)
    )


def test_urls_in_text_are_url_links_and_doi_urls_are_doi_links():
    text = (
        "Data at https://example.org/data/set-1. The original "
        "(https://doi.org/10.1371/journal.pone.0258361) and http://dx.doi.org/10.1/ABC."
    )

    assert [(link.doi, link.url) for link in text_links(text)] == [
        (None, "https://example.org/data/set-1"),
        ("10.1371/journal.pone.0258361", None),
        ("10.1/abc", None),
    ]


def test_text_without_links_gives_none():
    assert text_links("This article has been retracted.") == []
    assert text_links("") == []


def test_each_doi_and_url_is_kept_once_the_first_wins():
    first = Link(doi="10.1/x", url=None, role="update-to", source=LinkSource.CROSSREF)
    again = Link(doi="10.1/x", url=None, role=None, source=LinkSource.EUROPEPMC_TEXT)
    url = Link(doi=None, url="https://example.org", role=None, source=LinkSource.CROSSREF)
    url_again = Link(doi=None, url="https://example.org", role=None, source=LinkSource.EUROPEPMC_XML)

    assert unique([first, url, again, url_again]) == [first, url]


def test_a_doi_inside_another_url_is_also_a_doi_link():
    text = "Read it at https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0258361."

    assert [(link.doi, link.url) for link in text_links(text)] == [
        (None, "https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0258361"),
        ("10.1371/journal.pone.0258361", None),
    ]
