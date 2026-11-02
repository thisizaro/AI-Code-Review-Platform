from app.models import RepoFile


def make_file(path: str, content: str, language: str | None = None) -> RepoFile:
    if language is None:
        ext = path.rsplit(".", 1)[-1] if "." in path else ""
        language = {"py": "python", "java": "java", "js": "javascript", "jsx": "javascript", "ts": "typescript"}.get(ext, ext or "text")
    return RepoFile(path=path, language=language, size=len(content), content=content)


def make_repo(files: list[RepoFile]) -> dict:
    return {
        "url": "https://github.com/acme/demo",
        "owner": "acme",
        "name": "demo",
        "sha": "abc123",
        "files": [f.model_dump() for f in files],
    }
