from app.llm.client import LLMError
from app.models import AskResponse, Repo
from app.retrieval import Chunk, rank_chunks
from app.security import redact_secrets

MAX_CHUNK_CHARS = 1500
MAX_CONTEXT_CHARS = 9000

SYSTEM = (
    "You are a code assistant helping someone understand a repository. Answer ONLY from the provided snippets. "
    "If the snippets do not contain the answer, say it was not found in the provided files. "
    "Mention file paths when relevant and keep the answer concise."
)


def _format(chunks: list[Chunk]) -> str:
    parts, used = [], 0
    for c in chunks:
        block = f"### {c.path} (from line {c.start_line})\n{redact_secrets(c.text[:MAX_CHUNK_CHARS], c.path)}\n"
        if used + len(block) > MAX_CONTEXT_CHARS:
            break
        parts.append(block)
        used += len(block)
    return "\n".join(parts)


def answer_question(repo: Repo, question: str, llm) -> AskResponse:
    chunks = rank_chunks(repo.files, question)
    if not chunks:
        return AskResponse(answer="Not found in the provided files. Try rephrasing the question or naming a file, class or feature.", sources=[])
    sources = list(dict.fromkeys(c.path for c in chunks))
    context = _format(chunks)
    if llm.available:
        try:
            answer = llm.complete(SYSTEM, f"Repository snippets:\n\n{context}\n\nQuestion: {question}").strip()
            if answer:
                return AskResponse(answer=answer, sources=sources)
        except LLMError:
            pass
    return AskResponse(
        answer="LLM answers are unavailable right now (no API key, or the provider failed), so here are the most relevant code snippets:\n\n" + context,
        sources=sources,
    )
