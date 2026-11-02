from app.skills import compute_match_score, extract_repo_skills, keyword_skills, normalize_skill
from tests.helpers import make_file


def test_normalize_aliases_and_whitespace():
    assert normalize_skill(" JS ") == "javascript"
    assert normalize_skill("Postgres") == "postgresql"
    assert normalize_skill("K8s") == "kubernetes"
    assert normalize_skill("Spring Boot") == "spring boot"
    assert normalize_skill("Machine Learning") == "machine learning"


def test_repo_skills_from_python_manifest_and_infra_files():
    files = [
        make_file("main.py", "print('hi')\n"),
        make_file("requirements.txt", "fastapi==0.1\npandas\npsycopg2-binary\n"),
        make_file("Dockerfile", "FROM python:3.12\n"),
        make_file(".github/workflows/ci.yml", "name: ci\n"),
    ]
    skills = extract_repo_skills(files)
    assert {"python", "fastapi", "pandas", "postgresql", "docker", "ci/cd"} <= skills


def test_repo_skills_from_js_and_java_manifests():
    files = [
        make_file("package.json", '{"dependencies": {"react": "^18", "jest": "1"}}'),
        make_file("src/App.jsx", "export default 1"),
        make_file("pom.xml", "<artifactId>spring-boot-starter-web</artifactId><artifactId>junit-jupiter</artifactId>"),
        make_file("A.java", "class A {}"),
    ]
    skills = extract_repo_skills(files)
    assert {"react", "jest", "javascript", "spring boot", "junit", "java", "maven"} <= skills


def test_repo_skills_detect_rest_apis_from_code():
    files = [make_file("api.py", "from fastapi import APIRouter\nrouter = APIRouter()\n@router.get('/x')\ndef x(): ...\n")]
    assert "rest apis" in extract_repo_skills(files)


def test_repo_skills_empty_for_no_evidence():
    assert extract_repo_skills([make_file("notes.txt", "hello")]) == set()


def test_keyword_skills_uses_word_boundaries():
    assert keyword_skills("We need Python, Postgres and k8s experience") == {"python", "postgresql", "kubernetes"}
    assert "go" not in keyword_skills("Good communication and going the extra mile")
    assert keyword_skills("Strong JavaScript skills") == {"javascript"}


def test_score_weights_required_double_preferred():
    score, matched, missing = compute_match_score(["python", "sql"], ["docker"], {"python", "docker"})
    assert score == 60
    assert matched == ["python", "docker"]
    assert missing == ["sql"]


def test_score_bounds():
    assert compute_match_score(["python"], [], {"python"})[0] == 100
    assert compute_match_score(["python"], ["sql"], set())[0] == 0


def test_score_ignores_case_and_aliases_in_inputs():
    score, matched, _ = compute_match_score(["Postgres"], [], {"postgresql"})
    assert score == 100 and matched == ["postgresql"]
