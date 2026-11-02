from app.qa import answer_question
from app.models import Repo
from app.retrieval import chunk_file, rank_chunks
from tests.fakes import FakeLLM
from tests.helpers import make_file

ROUTES = make_file("src/routes.py", "from fastapi import APIRouter\nrouter = APIRouter()\n\n@router.get('/users')\ndef list_users():\n    return []\n")
AUTH = make_file("src/auth.py", "def login(user, password):\n    # authentication logic checks the password hash\n    return check(user, password)\n")
MATHY = make_file("src/mathy.py", "def add(a, b):\n    return a + b\n")
FILES = [ROUTES, AUTH, MATHY]


def test_chunk_file_splits_long_files_and_tracks_start_lines():
    content = "\n".join(f"line{i}" for i in range(100))
    chunks = chunk_file("a.py", content, max_lines=40)
    assert [c.start_line for c in chunks] == [1, 41, 81]
    assert all(c.path == "a.py" for c in chunks)


def test_rank_puts_relevant_file_first():
    top = rank_chunks(FILES, "Where are the API routes defined?", k=2)
    assert top[0].path == "src/routes.py"


def test_rank_matches_on_content_words():
    assert rank_chunks(FILES, "how does authentication work", k=1)[0].path == "src/auth.py"


def test_rank_matches_on_path_words():
    files = [make_file("src/payments.py", "def run(): pass\n"), MATHY]
    assert rank_chunks(files, "payments", k=1)[0].path == "src/payments.py"


def test_rank_returns_nothing_without_overlap():
    assert rank_chunks(FILES, "kubernetes helm charts", k=3) == []


def test_rank_respects_k():
    assert len(rank_chunks(FILES, "def return", k=1)) == 1


def test_answer_uses_llm_and_returns_sources_in_rank_order():
    llm = FakeLLM(text="Routes live in src/routes.py")
    result = answer_question(Repo(url="u", files=FILES), "Where are the API routes defined?", llm)
    assert result.answer == "Routes live in src/routes.py"
    assert result.sources[0] == "src/routes.py"
    system, user = llm.prompts[0]
    assert "Where are the API routes defined?" in user
    assert "router.get" in user
    assert "def add(a, b)" not in user
    assert "only" in system.lower()


def test_answer_prompt_is_bounded():
    big = make_file("src/routes.py", "routes api router\n" * 5000)
    llm = FakeLLM()
    answer_question(Repo(url="u", files=[big]), "api routes", llm)
    assert len(llm.prompts[0][1]) < 14000


def test_answer_without_llm_returns_snippets_and_sources():
    result = answer_question(Repo(url="u", files=FILES), "API routes", FakeLLM(available=False))
    assert "src/routes.py" in result.sources
    assert "router.get" in result.answer
    assert "LLM" in result.answer


def test_answer_when_llm_call_fails_falls_back_to_snippets():
    result = answer_question(Repo(url="u", files=FILES), "API routes", FakeLLM(fail_text=True))
    assert "src/routes.py" in result.sources


def test_answer_when_nothing_relevant_skips_llm():
    llm = FakeLLM()
    result = answer_question(Repo(url="u", files=FILES), "kubernetes helm charts", llm)
    assert result.sources == []
    assert "not found" in result.answer.lower()
    assert llm.prompts == []


SECRET_FILE = make_file("src/config.py", "# aws credentials for the deploy script\nAWS_KEY = 'AKIAIOSFODNN7EXAMPLE'\n")


def test_llm_prompt_never_contains_secret_values():
    llm = FakeLLM()
    answer_question(Repo(url="u", files=[SECRET_FILE]), "what are the aws credentials", llm)
    assert llm.prompts and all("AKIAIOSFODNN7EXAMPLE" not in system + user for system, user in llm.prompts)


def test_fallback_answer_never_contains_secret_values():
    result = answer_question(Repo(url="u", files=[SECRET_FILE]), "what are the aws credentials", FakeLLM(available=False))
    assert "AKIAIOSFODNN7EXAMPLE" not in result.answer
    assert "src/config.py" in result.sources


def test_weakly_related_chunks_are_dropped():
    strong = make_file("strong.py", "api routes\n" * 6)
    weak = make_file("weak.py", "x = 1\n" * 35 + "# api\n")
    assert [c.path for c in rank_chunks([strong, weak], "api routes", k=6)] == ["strong.py"]
