# Updating (docs/ARCHITECTURE.md, Section 4). Build from backend/:
#   docker build -f updating.Dockerfile -t updating .
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

# Updating keeps nothing on disk (its tables are in Postgres), so it doesn't need root
RUN useradd --system app
USER app

EXPOSE 8001

# One worker only: the poll scheduler and its lock live in this process (updating/main.py)
CMD ["uvicorn", "updating.main:app", "--host", "0.0.0.0", "--port", "8001"]
