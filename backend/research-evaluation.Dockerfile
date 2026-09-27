# Research Evaluation (docs/ARCHITECTURE.md, Section 3). Build from backend/:
#   docker build -f research-evaluation.Dockerfile -t research-evaluation .
FROM python:3.13-slim

# pinned, so a new uv release can't change what the same commit builds
COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /bin/

WORKDIR /app

# Install deps first so this layer caches across code-only changes.
# --locked fails the build if uv.lock is out of date, instead of resolving new versions.
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --locked --no-install-project --no-dev

COPY src/ ./src/

RUN uv sync --locked --no-dev

ENV PATH="/app/.venv/bin:${PATH}"

EXPOSE 8000

CMD ["uvicorn", "research_evaluation.main:app", "--host", "0.0.0.0", "--port", "8000"]
