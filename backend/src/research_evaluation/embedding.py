"""Text embeddings for the paper's past (docs/EVALUATION.md, "Embeddings"): Gemini
`gemini-embedding-001` at 1536 dimensions, L2-normalised here because Gemini normalises only its
full 3072-dimension vectors. Stored text is embedded as RETRIEVAL_DOCUMENT and a search's query as
RETRIEVAL_QUERY. The rest of Research Evaluation talks to the `Embedder` protocol, so tests can put a
fake in its place. Also the chunker that cuts a document's text into pieces to embed."""

import math
from dataclasses import dataclass
from typing import Literal, Protocol

from google import genai
from google.genai import types

# what Storage Management's vector(1536) column holds
EMBED_DIMENSIONS = 1536
# the most texts Gemini embeds in one request
BATCH_SIZE = 100
# chunk_text's default chunk length and how much each chunk repeats of the one before
CHUNK_SIZE = 3200
CHUNK_OVERLAP = 400

type EmbedTask = Literal["document", "query"]

TASK_TYPES: dict[str, str] = {"document": "RETRIEVAL_DOCUMENT", "query": "RETRIEVAL_QUERY"}


class Embedder(Protocol):
    @property
    def model(self) -> str: ...

    async def embed(self, texts: list[str], task: EmbedTask) -> list[list[float]]: ...


@dataclass(frozen=True)
class GeminiEmbedder:
    """The Embedder Research Evaluation runs with: a Gemini client and EMBED_MODEL."""

    client: genai.Client
    model: str

    async def embed(self, texts: list[str], task: EmbedTask) -> list[list[float]]:
        """One unit-length vector of EMBED_DIMENSIONS numbers per text, in order; raises
        ValueError when Gemini's answer has the wrong number or size of vectors."""
        vectors: list[list[float]] = []
        for start in range(0, len(texts), BATCH_SIZE):
            batch = texts[start : start + BATCH_SIZE]
            response = await self.client.aio.models.embed_content(
                model=self.model,
                contents=batch,
                config=types.EmbedContentConfig(
                    task_type=TASK_TYPES[task], output_dimensionality=EMBED_DIMENSIONS
                ),
            )
            embeddings = response.embeddings or []
            if len(embeddings) != len(batch):
                raise ValueError(f"Gemini returned {len(embeddings)} embeddings for {len(batch)} texts")
            vectors.extend(normalise(embedding.values or []) for embedding in embeddings)
        return vectors


def normalise(values: list[float]) -> list[float]:
    if len(values) != EMBED_DIMENSIONS:
        raise ValueError(f"an embedding has {len(values)} numbers, not {EMBED_DIMENSIONS}")
    norm = math.sqrt(sum(value * value for value in values))
    if norm == 0:
        raise ValueError("an embedding is all zeros")
    return [value / norm for value in values]


def chunk_text(text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[str]:
    """Pieces of at most `size` characters, each starting `overlap` characters before the previous
    one ends, so a sentence cut at one edge is whole in the next; none for blank text."""
    if not text.strip():
        return []
    step = size - overlap
    chunks = []
    for start in range(0, len(text), step):
        chunks.append(text[start : start + size])
        if start + size >= len(text):
            break
    return chunks
