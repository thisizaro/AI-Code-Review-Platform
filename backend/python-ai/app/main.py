import logging

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException

from app.analysis import analyze_repo
from app.config import Settings
from app.llm.client import LLMClient
from app.matching import NoSkillsFound, match_repo
from app.models import AnalyzeResponse, AskRequest, AskResponse, BundleRequest, MatchRequest, MatchResponse
from app.qa import answer_question

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def create_app(llm=None) -> FastAPI:
    if llm is None:
        load_dotenv()
        llm = LLMClient(Settings.from_env())
    app = FastAPI(title="RepoMind AI service")

    @app.get("/health")
    def health():
        settings = getattr(llm, "settings", None)
        provider = getattr(settings, "provider", "none")
        model = getattr(settings, "gemini_model" if provider == "gemini" else "groq_model", "none")
        return {"status": "ok", "llm_provider": provider, "llm_model": model, "llm_configured": llm.available}

    @app.post("/internal/analyze", response_model=AnalyzeResponse)
    def analyze(request: BundleRequest):
        return analyze_repo(request.repo, llm)

    @app.post("/internal/match", response_model=MatchResponse)
    def match(request: MatchRequest):
        try:
            return match_repo(request.repo, request.job_description, llm)
        except NoSkillsFound as e:
            raise HTTPException(status_code=400, detail=str(e)) from None

    @app.post("/internal/ask", response_model=AskResponse)
    def ask(request: AskRequest):
        return answer_question(request.repo, request.question, llm)

    return app


app = create_app()
