import pytest

from app.matching import NoSkillsFound, match_repo
from app.models import Repo
from tests.fakes import FakeLLM
from tests.helpers import make_file


def repo(*files):
    return Repo(url="https://github.com/a/b", files=list(files))


PY_REPO = repo(make_file("main.py", "x=1"), make_file("requirements.txt", "fastapi\n"), make_file("Dockerfile", "FROM x"))


def test_uses_llm_skills_and_normalizes_names():
    llm = FakeLLM(json_result={"required": ["Python", "Postgres"], "preferred": ["Docker"]}, text="Looks decent.")
    result = match_repo(PY_REPO, "Python dev with Postgres", llm)
    assert result.match_score == round(100 * (2 + 1) / (2 * 2 + 1))
    assert result.matched_skills == ["python", "docker"]
    assert result.missing_skills == ["postgresql"]
    assert result.explanation == "Looks decent."


def test_falls_back_to_keywords_when_llm_unavailable():
    llm = FakeLLM(available=False)
    result = match_repo(PY_REPO, "Need Python and Kubernetes experience", llm)
    assert result.matched_skills == ["python"]
    assert result.missing_skills == ["kubernetes"]
    assert result.match_score == 50
    assert "python" in result.explanation.lower()


def test_falls_back_to_keywords_when_llm_returns_bad_json():
    result = match_repo(PY_REPO, "Python please", FakeLLM(fail_json=True))
    assert result.matched_skills == ["python"]


def test_explanation_falls_back_to_template_when_llm_text_fails():
    llm = FakeLLM(json_result={"required": ["python"], "preferred": []}, fail_text=True)
    result = match_repo(PY_REPO, "Python", llm)
    assert result.match_score == 100
    assert result.explanation


def test_raises_when_job_description_has_no_skills():
    with pytest.raises(NoSkillsFound):
        match_repo(PY_REPO, "We are a friendly team that values kindness.", FakeLLM(available=False))


def test_llm_json_with_wrong_shape_is_ignored():
    result = match_repo(PY_REPO, "Python", FakeLLM(json_result=["not", "a", "dict"]))
    assert result.matched_skills == ["python"]


def test_repo_content_is_not_sent_to_llm_for_skill_extraction():
    llm = FakeLLM(json_result={"required": ["python"], "preferred": []})
    match_repo(repo(make_file("secret.py", "API_PASSWORD_SENTINEL = 1")), "Python", llm)
    assert all("API_PASSWORD_SENTINEL" not in system + user for system, user in llm.prompts)


def test_keyword_fallback_honours_required_and_nice_to_have_sentences():
    jd = "We need a Python developer with FastAPI. Required: Docker, AWS. Nice to have: Kubernetes, pytest."
    result = match_repo(PY_REPO, jd, FakeLLM(available=False))
    # required: python, fastapi, docker (matched) + aws (missing); preferred: kubernetes, pytest (both missing)
    assert result.match_score == round(100 * (2 * 3) / (2 * 4 + 2))
    assert set(result.missing_skills) == {"aws", "kubernetes", "pytest"}


def test_keyword_fallback_treats_a_plus_sentences_as_preferred():
    result = match_repo(PY_REPO, "Python is required.\nKubernetes is a plus.", FakeLLM(available=False))
    assert result.match_score == round(100 * 2 / (2 * 1 + 1))
