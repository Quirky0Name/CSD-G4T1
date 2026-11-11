"""embedding.py: the Gemini embedding call against a fake client, and the document chunker."""

import math
from itertools import pairwise
from types import SimpleNamespace

import pytest
from google.genai import types

from research_evaluation.embedding import EMBED_DIMENSIONS, GeminiEmbedder, chunk_text


class FakeModels:
    """Answers each text with a vector that isn't of length 1, as Gemini's are below 3072."""

    def __init__(self):
        self.calls: list[dict] = []

    async def embed_content(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            embeddings=[
                SimpleNamespace(values=[float(i + 1)] * EMBED_DIMENSIONS)
                for i, _ in enumerate(kwargs["contents"])
            ]
        )


def embedder(models: FakeModels) -> GeminiEmbedder:
    return GeminiEmbedder(
        SimpleNamespace(aio=SimpleNamespace(models=models)), "gemini-embedding-001"
    )


async def test_normalised():
    vectors = await embedder(FakeModels()).embed(["one", "two"], "document")

    assert len(vectors) == 2
    for vector in vectors:
        assert len(vector) == 1536
        assert math.sqrt(sum(value * value for value in vector)) == pytest.approx(1, abs=1e-6)


@pytest.mark.parametrize(
    ("task", "task_type"), [("document", "RETRIEVAL_DOCUMENT"), ("query", "RETRIEVAL_QUERY")]
)
async def test_request(task, task_type):
    models = FakeModels()

    await embedder(models).embed(["the text"], task)

    [call] = models.calls
    assert call["model"] == "gemini-embedding-001"
    assert call["contents"] == ["the text"]
    config: types.EmbedContentConfig = call["config"]
    assert config.task_type == task_type
    assert config.output_dimensionality == 1536


async def test_no_texts_makes_no_call():
    models = FakeModels()

    assert await embedder(models).embed([], "document") == []
    assert models.calls == []


async def test_many_texts_are_sent_in_batches_in_order():
    class NumberedModels(FakeModels):
        """Answers "text <n>" with a vector whose first value is n + 1 and the rest 1, so each
        text's vector is distinct after normalising."""

        async def embed_content(self, **kwargs):
            self.calls.append(kwargs)
            return SimpleNamespace(
                embeddings=[
                    SimpleNamespace(
                        values=[float(int(text.split()[1]) + 1)] + [1.0] * (EMBED_DIMENSIONS - 1)
                    )
                    for text in kwargs["contents"]
                ]
            )

    models = NumberedModels()

    vectors = await embedder(models).embed([f"text {i}" for i in range(250)], "document")

    assert [len(call["contents"]) for call in models.calls] == [100, 100, 50]
    assert [call["contents"][0] for call in models.calls] == ["text 0", "text 100", "text 200"]
    # normalising keeps the ratio of the first value to the second
    assert [round(vector[0] / vector[1]) for vector in vectors] == list(range(1, 251))


async def test_an_all_zero_vector_raises():
    class ZeroModels(FakeModels):
        async def embed_content(self, **kwargs):
            return SimpleNamespace(embeddings=[SimpleNamespace(values=[0.0] * EMBED_DIMENSIONS)])

    with pytest.raises(ValueError):
        await embedder(ZeroModels()).embed(["text"], "document")


async def test_an_answer_of_the_wrong_size_raises():
    class ShortModels(FakeModels):
        async def embed_content(self, **kwargs):
            return SimpleNamespace(embeddings=[SimpleNamespace(values=[1.0] * 768)])

    with pytest.raises(ValueError):
        await embedder(ShortModels()).embed(["text"], "document")


async def test_a_missing_embedding_raises():
    class FewerModels(FakeModels):
        async def embed_content(self, **kwargs):
            return SimpleNamespace(embeddings=[])

    with pytest.raises(ValueError):
        await embedder(FewerModels()).embed(["text"], "document")


def test_chunk_text():
    text = "".join(chr(ord("a") + i % 26) for i in range(7000))

    chunks = chunk_text(text, size=3200, overlap=400)

    assert all(len(chunk) <= 3200 for chunk in chunks)
    # each chunk starts 400 characters before the previous one ends, and together they cover the text
    assert chunks == [text[0:3200], text[2800:6000], text[5600:7000]]
    for previous, chunk in pairwise(chunks):
        assert previous[-400:] == chunk[:400]

    assert chunk_text("short text") == ["short text"]
    assert chunk_text("x" * 3200) == ["x" * 3200]
    assert chunk_text("") == []
    assert chunk_text("   \n ") == []
