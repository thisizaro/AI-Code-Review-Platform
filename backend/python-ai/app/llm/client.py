import hashlib
import json
import logging
import re
import time

import httpx

from app.config import Settings

log = logging.getLogger("repomind.llm")

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class LLMError(Exception):
    """The model answered, but the answer was unusable."""


class LLMUnavailable(LLMError):
    """No provider is configured, or every configured provider failed."""


class _Retryable(Exception):
    pass


class LLMClient:
    """Calls Groq and/or Gemini. The configured provider goes first, the other is a fallback."""

    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None):
        self.settings = settings
        self._http = httpx.Client(transport=transport, timeout=settings.request_timeout_seconds)
        self._cache: dict[str, str] = {}

    @property
    def providers(self) -> list[str]:
        order = ["gemini", "groq"] if self.settings.provider == "gemini" else ["groq", "gemini"]
        keys = {"groq": self.settings.groq_api_key, "gemini": self.settings.gemini_api_key}
        return [p for p in order if keys[p]]

    @property
    def available(self) -> bool:
        return bool(self.providers)

    def complete(self, system: str, user: str, json_mode: bool = False) -> str:
        if not self.available:
            raise LLMUnavailable("No LLM API key is configured")
        cache_key = hashlib.sha256(f"{json_mode}\x00{system}\x00{user}".encode()).hexdigest()
        if cache_key in self._cache:
            return self._cache[cache_key]
        for provider in self.providers:
            for attempt in (1, 2):
                try:
                    text = self._call(provider, system, user, json_mode)
                    self._cache[cache_key] = text
                    return text
                except _Retryable as e:
                    log.warning("llm provider=%s attempt=%d failed: %s", provider, attempt, e)
                    if attempt == 1:
                        time.sleep(self.settings.retry_backoff_seconds)
                except LLMError as e:
                    log.warning("llm provider=%s rejected request: %s", provider, e)
                    break
        raise LLMUnavailable("All configured LLM providers failed")

    def complete_json(self, system: str, user: str):
        text = self.complete(system, user, json_mode=True)
        try:
            return _parse_json(text)
        except ValueError:
            retry_user = user + "\n\nYour previous reply was not valid JSON. Reply with a single valid JSON value only."
            return _parse_json_or_raise(self.complete(system, retry_user, json_mode=True))

    def _call(self, provider: str, system: str, user: str, json_mode: bool) -> str:
        try:
            if provider == "groq":
                response = self._groq(system, user, json_mode)
            else:
                response = self._gemini(system, user, json_mode)
        except httpx.HTTPError as e:
            raise _Retryable(type(e).__name__) from None
        if response.status_code == 429 or response.status_code >= 500:
            raise _Retryable(f"HTTP {response.status_code}")
        if response.status_code >= 400:
            raise LLMError(f"HTTP {response.status_code}")
        try:
            data = response.json()
            if provider == "groq":
                return data["choices"][0]["message"]["content"]
            return data["candidates"][0]["content"]["parts"][0]["text"]
        except (ValueError, KeyError, IndexError, TypeError):
            raise LLMError("Unexpected response shape") from None

    def _groq(self, system: str, user: str, json_mode: bool) -> httpx.Response:
        body = {
            "model": self.settings.groq_model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0.2,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        return self._http.post(GROQ_URL, json=body, headers={"Authorization": f"Bearer {self.settings.groq_api_key}"})

    def _gemini(self, system: str, user: str, json_mode: bool) -> httpx.Response:
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {"temperature": 0.2},
        }
        if json_mode:
            body["generationConfig"]["responseMimeType"] = "application/json"
        url = GEMINI_URL.format(model=self.settings.gemini_model)
        return self._http.post(url, json=body, headers={"x-goog-api-key": self.settings.gemini_api_key})


def _parse_json(text: str):
    text = text.strip()
    fenced = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if fenced:
        text = fenced.group(1).strip()
    try:
        return json.loads(text)
    except ValueError:
        pass
    start = min((i for i in (text.find("{"), text.find("[")) if i != -1), default=-1)
    end = max(text.rfind("}"), text.rfind("]"))
    if start == -1 or end <= start:
        raise ValueError("no json")
    return json.loads(text[start:end + 1])


def _parse_json_or_raise(text: str):
    try:
        return _parse_json(text)
    except ValueError:
        raise LLMError("Model did not return valid JSON") from None
