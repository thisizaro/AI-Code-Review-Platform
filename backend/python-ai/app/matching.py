import re

from app.llm.client import LLMError
from app.models import MatchResponse, Repo
from app.skills import compute_match_score, extract_repo_skills, keyword_skills, normalize_skill

MAX_JD_CHARS = 6000
MAX_SKILLS = 25

EXTRACT_SYSTEM = (
    "You extract skills from job descriptions. Reply with JSON only, in the form "
    '{"required": ["skill"], "preferred": ["skill"]}. Use short canonical names such as "Python", "PostgreSQL", "Docker". '
    "Only include technical skills, tools, languages and frameworks."
)
EXPLAIN_SYSTEM = (
    "You write a 2 to 3 sentence factual assessment of how well a repository matches a job description. "
    "Use only the facts given. Missing skills mean limited evidence in the repository, not lack of candidate ability."
)


PREFERRED_MARKER = re.compile(r"(?i)nice[\s-]to[\s-]have|preferred|bonus|a plus|optional|desirable")
SENTENCE_SPLIT = re.compile(r"\n|;|(?<=[.!?])\s+")


class NoSkillsFound(Exception):
    pass


def _keyword_split(job_description: str) -> tuple[list[str], list[str]]:
    """Without an LLM: sentences mentioning 'nice to have', 'a plus' and similar make their skills preferred."""
    required: set[str] = set()
    preferred: set[str] = set()
    for sentence in SENTENCE_SPLIT.split(job_description):
        (preferred if PREFERRED_MARKER.search(sentence) else required).update(keyword_skills(sentence))
    return sorted(required), sorted(preferred - required)


def _clean(values) -> list[str]:
    if not isinstance(values, list):
        return []
    return [v.strip() for v in values if isinstance(v, str) and v.strip()][:MAX_SKILLS]


def _extract_jd_skills(job_description: str, llm) -> tuple[list[str], list[str]]:
    if llm.available:
        try:
            data = llm.complete_json(EXTRACT_SYSTEM, job_description[:MAX_JD_CHARS])
            if isinstance(data, dict):
                required, preferred = _clean(data.get("required")), _clean(data.get("preferred"))
                if required or preferred:
                    return required, preferred
        except LLMError:
            pass
    return _keyword_split(job_description)


def _template_explanation(matched: list[str], missing: list[str]) -> str:
    total = len(matched) + len(missing)
    found = ", ".join(matched) if matched else "none"
    text = f"The repository shows evidence of {len(matched)} of {total} requested skills ({found})."
    if missing:
        text += f" Not evidenced: {', '.join(missing)}. Missing skills indicate limited evidence in the inspected repository, not a lack of candidate ability."
    return text


def match_repo(repo: Repo, job_description: str, llm) -> MatchResponse:
    required, preferred = _extract_jd_skills(job_description, llm)
    if not required and not preferred:
        raise NoSkillsFound("No skills found in job description")
    repo_skills = extract_repo_skills(repo.files)
    score, matched, missing = compute_match_score(required, preferred, repo_skills)
    explanation = _template_explanation(matched, missing)
    if llm.available:
        facts = (f"Match score: {score}/100\nRequired skills: {[normalize_skill(s) for s in required]}\n"
                 f"Preferred skills: {[normalize_skill(s) for s in preferred]}\n"
                 f"Skills evidenced in repo: {matched}\nSkills not evidenced: {missing}")
        try:
            explanation = llm.complete(EXPLAIN_SYSTEM, facts).strip() or explanation
        except LLMError:
            pass
    return MatchResponse(match_score=score, matched_skills=matched, missing_skills=missing, explanation=explanation)
