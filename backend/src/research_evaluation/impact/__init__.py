"""Impact: an LLM (Gemini Flash) judges a report's alerts together, from the documents
investigation stored, and writes its evaluation into the report (docs/ARCHITECTURE.md).

Fetched text is data, never instructions (docs/DECISIONS.md): it goes into the prompt as
content, and the model's answer is parsed against a schema rather than acted on."""
