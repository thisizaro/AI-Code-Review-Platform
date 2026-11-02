from typing import Literal

from pydantic import BaseModel, Field, field_validator

Severity = Literal["High", "Medium", "Low", "Info"]
SEVERITY_ORDER = {"High": 0, "Medium": 1, "Low": 2, "Info": 3}


class RepoFile(BaseModel):
    path: str
    language: str = "text"
    size: int = 0
    content: str = ""


class Repo(BaseModel):
    url: str
    owner: str = ""
    name: str = ""
    sha: str = ""
    files: list[RepoFile] = Field(default_factory=list)


class Finding(BaseModel):
    severity: Severity
    title: str
    file: str
    line: int | None = None
    detail: str


class BundleRequest(BaseModel):
    repo: Repo


class AnalyzeResponse(BaseModel):
    issues: list[Finding]
    security_findings: list[Finding]
    functions_analyzed: int


class MatchRequest(BaseModel):
    repo: Repo
    job_description: str


class MatchResponse(BaseModel):
    match_score: int
    matched_skills: list[str]
    missing_skills: list[str]
    explanation: str


class AskRequest(BaseModel):
    repo: Repo
    question: str

    @field_validator("question")
    @classmethod
    def question_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("question must not be blank")
        return value.strip()


class AskResponse(BaseModel):
    answer: str
    sources: list[str]
