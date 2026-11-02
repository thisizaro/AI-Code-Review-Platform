from app.llm.client import LLMError, LLMUnavailable


class FakeLLM:
    """Stands in for LLMClient at the seam used by matching.py and qa.py."""

    def __init__(self, json_result=None, text="fake answer", available=True, fail_json=False, fail_text=False):
        self.json_result = json_result
        self.text = text
        self.available = available
        self.fail_json = fail_json
        self.fail_text = fail_text
        self.prompts: list[tuple[str, str]] = []

    def complete(self, system, user, json_mode=False):
        self.prompts.append((system, user))
        if not self.available or self.fail_text:
            raise LLMUnavailable("down")
        return self.text

    def complete_json(self, system, user):
        self.prompts.append((system, user))
        if not self.available:
            raise LLMUnavailable("down")
        if self.fail_json:
            raise LLMError("bad json")
        return self.json_result
