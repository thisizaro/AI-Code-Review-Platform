import { afterEach, describe, expect, it, vi } from "vitest";
import { analyzeRepository, askProjectQuestion } from "./api";

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

afterEach(() => vi.unstubAllGlobals());

describe("api service", () => {
  it("posts snake_case JSON and returns the parsed body", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { quality_score: 90 }));
    vi.stubGlobal("fetch", fetchMock);
    const result = await analyzeRepository("https://github.com/a/b");
    expect(result).toEqual({ quality_score: 90 });
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/repositories\/analyze$/);
    expect(JSON.parse(options.body)).toEqual({ repository_url: "https://github.com/a/b" });
  });

  it("surfaces the backend's message field on errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(404, { message: "Repository not found." })));
    await expect(analyzeRepository("https://github.com/a/b")).rejects.toThrow("Repository not found.");
  });

  it("falls back to a status message when the error body has no message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("boom", { status: 500, headers: { "content-type": "text/plain" } })));
    await expect(askProjectQuestion("https://github.com/a/b", "q")).rejects.toThrow("Request failed (500)");
  });

  it("explains when the API cannot be reached", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    await expect(analyzeRepository("https://github.com/a/b")).rejects.toThrow(/Cannot reach the API/);
  });

  it("explains when the request times out", async () => {
    const abort = Object.assign(new Error("aborted"), { name: "TimeoutError" });
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(abort));
    await expect(analyzeRepository("https://github.com/a/b")).rejects.toThrow(/took too long/);
  });
});
