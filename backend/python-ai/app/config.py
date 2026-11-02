import os
from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    provider: str = "groq"
    groq_api_key: str = ""
    groq_model: str = "openai/gpt-oss-120b"
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.0-flash"
    retry_backoff_seconds: float = 1.0
    request_timeout_seconds: float = 40.0

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env
        defaults = cls()
        return cls(
            provider=env.get("LLM_PROVIDER", defaults.provider).strip().lower() or defaults.provider,
            groq_api_key=env.get("GROQ_API_KEY", "").strip(),
            groq_model=env.get("GROQ_MODEL", defaults.groq_model),
            gemini_api_key=env.get("GEMINI_API_KEY", "").strip(),
            gemini_model=env.get("GEMINI_MODEL", defaults.gemini_model),
        )
