const GITHUB_REPO = /^https:\/\/github\.com\/([A-Za-z0-9._-]+)\/([A-Za-z0-9._-]+?)(?:\.git)?(?:\/[^?#]*)?(?:[?#].*)?$/;

/** Returns the canonical https://github.com/owner/repo URL, or null when the input is not a GitHub repository URL. */
export function normalizeRepoUrl(input = "") {
  const match = GITHUB_REPO.exec(input.trim());
  if (!match) return null;
  const [, owner, name] = match;
  if (/^\.+$/.test(owner) || /^\.+$/.test(name)) return null;
  return `https://github.com/${owner}/${name}`;
}
