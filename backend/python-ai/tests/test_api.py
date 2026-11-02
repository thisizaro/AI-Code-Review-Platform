from fastapi.testclient import TestClient

from app.config import Settings
from app.llm.client import LLMClient
from app.main import create_app
from tests.fakes import FakeLLM
from tests.helpers import make_file, make_repo

SECRET = "AKIAIOSFODNN7EXAMPLE"


def client(llm=None):
    return TestClient(create_app(llm or FakeLLM(available=False)))


def bundle(*files):
    return {"repo": make_repo(list(files))}


LONG_FN = "def big():\n" + "".join(f"    x{i} = {i}\n" for i in range(70))


def test_health_reports_provider_without_leaking_keys():
    body = client().get("/health").json()
    assert body["status"] == "ok"
    assert "llm_configured" in body and "llm_provider" in body
    assert "key" not in " ".join(body.keys()).replace("llm_configured", "")


def test_analyze_returns_issues_security_and_function_count():
    files = [make_file("a.py", LONG_FN), make_file("cfg.py", f"KEY = '{SECRET}'\n")]
    r = client().post("/internal/analyze", json=bundle(*files))
    assert r.status_code == 200
    body = r.json()
    assert any("Long function" in i["title"] for i in body["issues"])
    assert body["security_findings"][0]["severity"] == "High"
    assert body["security_findings"][0]["file"] == "cfg.py"
    assert body["security_findings"][0]["line"] == 1
    assert body["functions_analyzed"] == 1


def test_analyze_empty_repo_is_ok():
    r = client().post("/internal/analyze", json=bundle())
    assert r.status_code == 200
    assert r.json() == {"issues": [], "security_findings": [], "functions_analyzed": 0}


def test_analyze_rewrites_top_finding_details_with_llm_and_never_sends_code():
    llm = FakeLLM(json_result={"explanations": ["Plain English explanation one."]})
    files = [make_file("cfg.py", f"KEY = '{SECRET}'\n")]
    body = client(llm).post("/internal/analyze", json=bundle(*files)).json()
    assert body["security_findings"][0]["detail"] == "Plain English explanation one."
    assert llm.prompts, "LLM should have been called"
    assert all(SECRET not in system + user for system, user in llm.prompts)


def test_analyze_keeps_heuristic_details_when_llm_fails():
    files = [make_file("cfg.py", f"KEY = '{SECRET}'\n")]
    body = client(FakeLLM(fail_json=True)).post("/internal/analyze", json=bundle(*files)).json()
    assert "environment variable" in body["security_findings"][0]["detail"]


def test_analyze_ignores_llm_output_with_wrong_shape():
    files = [make_file("cfg.py", f"KEY = '{SECRET}'\n")]
    body = client(FakeLLM(json_result={"explanations": "nope"})).post("/internal/analyze", json=bundle(*files)).json()
    assert "environment variable" in body["security_findings"][0]["detail"]


def test_analyze_rejects_malformed_body():
    assert client().post("/internal/analyze", json={"nope": 1}).status_code == 422


def test_match_endpoint_returns_score_and_skills():
    files = [make_file("main.py", "x=1"), make_file("Dockerfile", "FROM x")]
    r = client().post("/internal/match", json={**bundle(*files), "job_description": "Python and Docker developer"})
    assert r.status_code == 200
    body = r.json()
    assert body["match_score"] == 100
    assert set(body["matched_skills"]) == {"python", "docker"}


def test_match_endpoint_returns_400_when_no_skills_in_jd():
    r = client().post("/internal/match", json={**bundle(), "job_description": "We value kindness."})
    assert r.status_code == 400
    assert r.json()["detail"] == "No skills found in job description"


def test_ask_endpoint_returns_answer_and_sources():
    files = [make_file("src/routes.py", "from fastapi import APIRouter\nrouter = APIRouter()\n")]
    llm = FakeLLM(text="They are in routes.py")
    r = client(llm).post("/internal/ask", json={**bundle(*files), "question": "Where are the API routes?"})
    assert r.status_code == 200
    assert r.json() == {"answer": "They are in routes.py", "sources": ["src/routes.py"]}


def test_ask_rejects_blank_question():
    r = client().post("/internal/ask", json={**bundle(), "question": "   "})
    assert r.status_code == 422


def test_health_includes_the_configured_model_name():
    llm = LLMClient(Settings(provider="gemini", gemini_model="gemini-test", groq_model="llama-test"))
    body = TestClient(create_app(llm)).get("/health").json()
    assert body["llm_model"] == "gemini-test"
    assert body["llm_provider"] == "gemini"
