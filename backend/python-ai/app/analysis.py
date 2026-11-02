from app.llm.client import LLMError
from app.models import SEVERITY_ORDER, AnalyzeResponse, Finding, Repo
from app.security import scan_security
from app.static_analysis import analyze_static

MAX_ENRICHED = 5

SYSTEM = (
    "You are a senior code reviewer. For each numbered finding, write one plain-English sentence (max 30 words) "
    "explaining the risk and how to fix it. You are given only the finding title, file and line, never the code. "
    'Reply with JSON only: {"explanations": ["sentence for finding 1", "sentence for finding 2"]}'
)


def _enrich(issues: list[Finding], security: list[Finding], llm) -> None:
    """Rewrite the details of the most severe findings in place. Sends titles and locations only, never source code."""
    ranked = sorted([*security, *issues], key=lambda f: SEVERITY_ORDER[f.severity])[:MAX_ENRICHED]
    if not ranked or not llm.available:
        return
    prompt = "\n".join(f"{n}. [{f.severity}] {f.title} in {f.file}" + (f" line {f.line}" if f.line else "") for n, f in enumerate(ranked, 1))
    try:
        data = llm.complete_json(SYSTEM, prompt)
    except LLMError:
        return
    explanations = data.get("explanations") if isinstance(data, dict) else None
    if not isinstance(explanations, list):
        return
    for finding, text in zip(ranked, explanations):
        if isinstance(text, str) and text.strip():
            finding.detail = text.strip()


def analyze_repo(repo: Repo, llm) -> AnalyzeResponse:
    issues, functions = analyze_static(repo.files)
    security = scan_security(repo.files)
    _enrich(issues, security, llm)
    return AnalyzeResponse(issues=issues, security_findings=security, functions_analyzed=functions)
