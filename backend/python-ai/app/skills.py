import json
import re

from app.models import RepoFile

# canonical skill -> words that mean the same thing in text
SKILL_ALIASES: dict[str, list[str]] = {
    "python": [], "java": [], "javascript": ["js", "ecmascript"], "typescript": ["ts"], "go": ["golang"],
    "rust": [], "c++": ["cpp"], "c#": ["csharp"], "kotlin": [], "php": [], "ruby": [],
    "react": ["reactjs", "react.js"], "vue": ["vuejs", "vue.js"], "angular": [], "node.js": ["nodejs", "node"],
    "spring boot": ["springboot", "spring"], "fastapi": [], "flask": [], "django": [], "express": ["expressjs"],
    "sql": [], "postgresql": ["postgres"], "mysql": [], "mongodb": ["mongo"], "redis": [], "kafka": [],
    "docker": [], "kubernetes": ["k8s"], "aws": ["amazon web services"], "azure": [], "gcp": ["google cloud"],
    "ci/cd": ["cicd", "ci cd", "continuous integration"], "git": [], "linux": [], "graphql": [],
    "rest apis": ["rest api", "restful", "restful api", "restful apis", "rest"], "microservices": ["microservice"],
    "machine learning": ["ml"], "pandas": [], "numpy": [], "pytorch": ["torch"], "tensorflow": [],
    "junit": [], "pytest": [], "jest": [], "maven": [], "gradle": [], "html": [], "css": [],
}
# These words are too ambiguous in plain English to match on their own when scanning a job description
AMBIGUOUS_ALIASES = {"rest", "node", "spring"}
AMBIGUOUS_CANONICAL = {"go"}  # only "golang" counts for Go

_ALIAS_TO_CANONICAL = {a: c for c, aliases in SKILL_ALIASES.items() for a in aliases}


def normalize_skill(name: str) -> str:
    cleaned = re.sub(r"\s+", " ", name.strip().lower())
    if cleaned in SKILL_ALIASES:
        return cleaned
    return _ALIAS_TO_CANONICAL.get(cleaned, cleaned)


def _term_regex(term: str) -> re.Pattern:
    return re.compile(rf"(?<![a-z0-9]){re.escape(term)}(?![a-z0-9])")


_KEYWORD_PATTERNS = [
    (canon, _term_regex(term))
    for canon, aliases in SKILL_ALIASES.items()
    for term in [canon, *aliases]
    if term not in AMBIGUOUS_ALIASES and term not in AMBIGUOUS_CANONICAL
]


def keyword_skills(text: str) -> set[str]:
    lowered = text.lower()
    return {canon for canon, pattern in _KEYWORD_PATTERNS if pattern.search(lowered)}


EXT_SKILLS = {
    "py": "python", "java": "java", "js": "javascript", "jsx": "javascript", "ts": "typescript", "tsx": "typescript",
    "go": "go", "rs": "rust", "kt": "kotlin", "php": "php", "rb": "ruby", "cs": "c#", "html": "html", "css": "css",
    "sql": "sql", "cpp": "c++",
}
MANIFESTS = {"requirements.txt", "pyproject.toml", "setup.py", "pipfile", "package.json", "pom.xml", "build.gradle",
             "build.gradle.kts", "go.mod", "cargo.toml", "gemfile", "composer.json"}
# dependency/import token -> skill
TOKEN_SKILLS = {
    "fastapi": "fastapi", "flask": "flask", "django": "django", "pandas": "pandas", "numpy": "numpy",
    "torch": "pytorch", "pytorch": "pytorch", "tensorflow": "tensorflow", "sklearn": "machine learning",
    "scikit-learn": "machine learning", "pytest": "pytest", "sqlalchemy": "sql", "psycopg2": "postgresql",
    "postgresql": "postgresql", "mysql": "mysql", "pymongo": "mongodb", "mongodb": "mongodb", "redis": "redis",
    "boto3": "aws", "aws-sdk": "aws", "react": "react", "vue": "vue", "angular": "angular", "express": "express",
    "jest": "jest", "typescript": "typescript", "spring-boot": "spring boot", "springframework": "spring boot",
    "junit": "junit", "hibernate": "sql", "kafka": "kafka", "graphql": "graphql",
}
_TOKEN_PATTERNS = [(skill, _term_regex(token)) for token, skill in TOKEN_SKILLS.items()]
REST_PATTERN = re.compile(r"@(app|router)\.(get|post|put|delete|patch)\b|@RestController|@(Get|Post|Put|Delete|Request)Mapping|\b(app|router)\.(get|post|put|delete)\(")
SQL_PATTERN = re.compile(r"(?i)\bselect\b.+\bfrom\b|\bcreate table\b|\binsert into\b")
IMPORT_LINE = re.compile(r"^\s*(import|from|using|require)\b.*$|require\(['\"][^'\"]+['\"]\)", re.M)


def _ext(path: str) -> str:
    return path.rsplit(".", 1)[-1].lower() if "." in path.rsplit("/", 1)[-1] else ""


def extract_repo_skills(files: list[RepoFile]) -> set[str]:
    skills: set[str] = set()
    evidence: list[str] = []
    for f in files:
        base = f.path.rsplit("/", 1)[-1]
        lower_base = base.lower()
        if _ext(f.path) in EXT_SKILLS:
            skills.add(EXT_SKILLS[_ext(f.path)])
        if lower_base in MANIFESTS:
            evidence.append(f.content.lower())
        if lower_base == "pom.xml":
            skills.add("maven")
        if lower_base.startswith("build.gradle"):
            skills.add("gradle")
        if lower_base == "dockerfile" or lower_base.startswith("docker-compose"):
            skills.add("docker")
        if (f.path.startswith(".github/workflows/") or lower_base in {".gitlab-ci.yml", "jenkinsfile"}
                or f.path.startswith(".circleci/")):
            skills.add("ci/cd")
        if _ext(f.path) in {"yml", "yaml"} and re.search(r"(?m)^kind:\s*(Deployment|StatefulSet|Service)\b", f.content):
            skills.add("kubernetes")
        if lower_base == "chart.yaml":
            skills.add("kubernetes")
        if _ext(f.path) in {"py", "java", "js", "jsx", "ts", "tsx", "go", "kt"}:
            evidence.append("\n".join(m.group(0).lower() for m in IMPORT_LINE.finditer(f.content)))
            if REST_PATTERN.search(f.content):
                skills.add("rest apis")
            if SQL_PATTERN.search(f.content):
                skills.add("sql")
    blob = "\n".join(evidence)
    for skill, pattern in _TOKEN_PATTERNS:
        if pattern.search(blob):
            skills.add(skill)
    return skills


def compute_match_score(required: list[str], preferred: list[str], repo_skills: set[str]) -> tuple[int, list[str], list[str]]:
    repo = {normalize_skill(s) for s in repo_skills}
    req = list(dict.fromkeys(normalize_skill(s) for s in required if s.strip()))
    pref = [p for p in dict.fromkeys(normalize_skill(s) for s in preferred if s.strip()) if p not in req]
    denominator = 2 * len(req) + len(pref)
    if denominator == 0:
        return 0, [], []
    matched_req = [s for s in req if s in repo]
    matched_pref = [s for s in pref if s in repo]
    score = round(100 * (2 * len(matched_req) + len(matched_pref)) / denominator)
    missing = [s for s in [*req, *pref] if s not in repo]
    return score, [*matched_req, *matched_pref], missing
