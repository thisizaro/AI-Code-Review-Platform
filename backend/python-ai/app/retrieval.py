import math
import re
from dataclasses import dataclass

from app.models import RepoFile

STOPWORDS = {
    "the", "a", "an", "is", "are", "was", "were", "be", "to", "of", "in", "on", "for", "and", "or", "how", "what",
    "where", "which", "who", "why", "when", "does", "do", "did", "this", "that", "it", "its", "with", "from", "by",
    "as", "at", "me", "my", "i", "you", "can", "explain", "tell", "about", "project", "repo", "repository", "code",
    "work", "works", "use", "used", "there", "any", "all",
}


MIN_RELATIVE_SCORE = 0.3  # drop chunks scoring under 30 percent of the best match


@dataclass(frozen=True)
class Chunk:
    path: str
    start_line: int
    text: str


def _stem(word: str) -> str:
    if word.endswith("ies") and len(word) > 4:
        return word[:-3] + "y"
    if word.endswith("es") and len(word) > 4 and word[-3] in "sxz":
        return word[:-2]
    if word.endswith("s") and not word.endswith("ss") and len(word) > 3:
        return word[:-1]
    return word


def tokenize(text: str) -> list[str]:
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    words = re.findall(r"[a-z0-9]+", spaced.lower())
    return [_stem(w) for w in words if len(w) > 1 and w not in STOPWORDS]


def chunk_file(path: str, content: str, max_lines: int = 40) -> list[Chunk]:
    lines = content.splitlines()
    return [Chunk(path, i + 1, "\n".join(lines[i:i + max_lines])) for i in range(0, len(lines), max_lines)]


def rank_chunks(files: list[RepoFile], question: str, k: int = 6) -> list[Chunk]:
    query = set(tokenize(question))
    if not query:
        return []
    chunks = [c for f in files for c in chunk_file(f.path, f.content)]
    docs = [tokenize(c.path) * 3 + tokenize(c.text) for c in chunks]
    if not docs:
        return []
    n = len(docs)
    avg_len = sum(len(d) for d in docs) / n or 1
    doc_freq = {t: sum(1 for d in docs if t in d) for t in query}
    k1, b = 1.5, 0.75
    scored: list[tuple[float, int]] = []
    for i, doc in enumerate(docs):
        score = 0.0
        for term in query:
            tf = doc.count(term)
            if tf == 0:
                continue
            idf = math.log(1 + (n - doc_freq[term] + 0.5) / (doc_freq[term] + 0.5))
            score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len(doc) / avg_len))
        if score > 0:
            scored.append((score, i))
    scored.sort(key=lambda s: (-s[0], s[1]))
    if scored:
        floor = scored[0][0] * MIN_RELATIVE_SCORE
        scored = [s for s in scored if s[0] >= floor]
    return [chunks[i] for _, i in scored[:k]]
