# One image, two apps. Which app runs is picked at container-start time
# via the `command:` override in docker-compose (see docker-compose.dev.yml).
FROM python:3.13-slim

# uv: fast installer, matches the .python-version / uv.lock used in dev.
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

WORKDIR /app

# Install deps first so this layer caches across code-only changes.
COPY pyproject.toml uv.lock* .python-version ./
RUN uv sync --frozen --no-install-project --no-dev || uv sync --no-install-project --no-dev

COPY src/ ./src/

RUN uv sync --frozen --no-dev || uv sync --no-dev

ENV PATH="/app/.venv/bin:${PATH}"

EXPOSE 8000

# Overridden per-service in docker-compose (research_evaluation vs updating).
CMD ["uvicorn", "research_evaluation.main:app", "--host", "0.0.0.0", "--port", "8000"]
