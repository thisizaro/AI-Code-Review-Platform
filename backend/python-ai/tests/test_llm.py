import json

import httpx
import pytest

from app.config import Settings
from app.llm.client import LLMClient, LLMError, LLMUnavailable


def groq_ok(text):
    return httpx.Response(200, json={"choices": [{"message": {"content": text}}]})


def gemini_ok(text):
    return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": text}]}}]})


def settings(**kw):
    base = dict(provider="groq", groq_api_key="gk-secret", groq_model="llama-test",
                gemini_api_key="mk-secret", gemini_model="gemini-test", retry_backoff_seconds=0)
    base.update(kw)
    return Settings(**base)


def client_with(handler, **kw):
    return LLMClient(settings(**kw), transport=httpx.MockTransport(handler))


def test_settings_from_env_defaults_and_overrides():
    s = Settings.from_env({"LLM_PROVIDER": "gemini", "GROQ_API_KEY": "a", "GEMINI_MODEL": "m"})
    assert s.provider == "gemini" and s.groq_api_key == "a" and s.gemini_model == "m"
    d = Settings.from_env({})
    assert d.provider == "groq" and d.groq_api_key == "" and d.groq_model


def test_unavailable_without_any_key():
    c = LLMClient(settings(groq_api_key="", gemini_api_key=""), transport=httpx.MockTransport(lambda r: groq_ok("x")))
    assert c.available is False
    with pytest.raises(LLMUnavailable):
        c.complete("sys", "hi")


def test_groq_request_shape_and_response():
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["authorization"]
        seen["body"] = json.loads(request.content)
        return groq_ok("hello")

    assert client_with(handler).complete("be brief", "say hi") == "hello"
    assert seen["url"] == "https://api.groq.com/openai/v1/chat/completions"
    assert seen["auth"] == "Bearer gk-secret"
    assert seen["body"]["model"] == "llama-test"
    assert seen["body"]["messages"][0] == {"role": "system", "content": "be brief"}
    assert seen["body"]["messages"][1] == {"role": "user", "content": "say hi"}


def test_gemini_used_first_when_configured_as_provider():
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["key"] = request.headers.get("x-goog-api-key")
        seen["body"] = json.loads(request.content)
        return gemini_ok("from gemini")

    assert client_with(handler, provider="gemini").complete("sys", "hi") == "from gemini"
    assert "gemini-test:generateContent" in seen["url"]
    assert seen["key"] == "mk-secret"
    assert seen["body"]["systemInstruction"]["parts"][0]["text"] == "sys"
    assert seen["body"]["contents"][0]["parts"][0]["text"] == "hi"


def test_retries_once_on_429_then_succeeds_without_fallback():
    calls = []

    def handler(request):
        calls.append(request.url.host)
        return httpx.Response(429) if len(calls) == 1 else groq_ok("ok")

    assert client_with(handler).complete("s", "u") == "ok"
    assert calls == ["api.groq.com", "api.groq.com"]


def test_falls_back_to_gemini_when_groq_keeps_failing():
    calls = []

    def handler(request):
        calls.append(request.url.host)
        if request.url.host == "api.groq.com":
            return httpx.Response(429)
        return gemini_ok("fallback answer")

    assert client_with(handler).complete("s", "u") == "fallback answer"
    assert calls == ["api.groq.com", "api.groq.com", "generativelanguage.googleapis.com"]


def test_all_providers_failing_raises_without_leaking_keys():
    c = client_with(lambda r: httpx.Response(500, text="boom gk-secret"))
    with pytest.raises(LLMUnavailable) as exc:
        c.complete("s", "u")
    assert "gk-secret" not in str(exc.value) and "mk-secret" not in str(exc.value)


def test_only_one_provider_configured_does_not_try_the_other():
    calls = []

    def handler(request):
        calls.append(request.url.host)
        return httpx.Response(500)

    c = client_with(handler, gemini_api_key="")
    with pytest.raises(LLMUnavailable):
        c.complete("s", "u")
    assert set(calls) == {"api.groq.com"}


def test_identical_prompts_are_cached():
    calls = []

    def handler(request):
        calls.append(1)
        return groq_ok("once")

    c = client_with(handler)
    assert c.complete("s", "u") == "once"
    assert c.complete("s", "u") == "once"
    assert len(calls) == 1


def test_complete_json_parses_plain_and_fenced_json():
    plain = client_with(lambda r: groq_ok('{"a": 1}'))
    assert plain.complete_json("s", "u") == {"a": 1}
    fenced = client_with(lambda r: groq_ok('Here you go:\n```json\n{"a": 2}\n```'))
    assert fenced.complete_json("s", "u") == {"a": 2}


def test_complete_json_retries_once_on_invalid_then_raises():
    calls = []

    def handler(request):
        calls.append(1)
        return groq_ok("not json at all")

    with pytest.raises(LLMError):
        client_with(handler).complete_json("s", "u")
    assert len(calls) == 2


def test_complete_json_recovers_when_retry_returns_valid_json():
    answers = iter(["garbage", '{"ok": true}'])
    assert client_with(lambda r: groq_ok(next(answers))).complete_json("s", "u") == {"ok": True}
