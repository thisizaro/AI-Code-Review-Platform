import { describe, expect, it } from "vitest";
import { normalizeRepoUrl } from "./repoUrl";

describe("normalizeRepoUrl", () => {
  it.each([
    "https://github.com/acme/demo",
    "https://github.com/acme/demo/",
    "https://github.com/acme/demo.git",
    "https://github.com/acme/demo/tree/main/src",
    "https://github.com/acme/demo?tab=readme",
    "  https://github.com/acme/demo  "
  ])("normalizes %s", (input) => {
    expect(normalizeRepoUrl(input)).toBe("https://github.com/acme/demo");
  });

  it("keeps dots, dashes and underscores in names", () => {
    expect(normalizeRepoUrl("https://github.com/my-org/my.repo_name")).toBe("https://github.com/my-org/my.repo_name");
  });

  it.each([
    "",
    "http://github.com/acme/demo",
    "https://gitlab.com/acme/demo",
    "https://github.com.evil.com/acme/demo",
    "https://github.com/acme",
    "https://github.com/ac me/demo",
    "not a url"
  ])("rejects %s", (input) => {
    expect(normalizeRepoUrl(input)).toBeNull();
  });
});
