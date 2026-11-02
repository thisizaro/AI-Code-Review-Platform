import { useState } from "react";
import { Github, ArrowRight } from "lucide-react";
import { normalizeRepoUrl } from "../utils/repoUrl";

export default function RepositoryForm({ onSubmit, loading = false, buttonText = "Analyze repository" }) {
  const [url, setUrl] = useState("");
  const [error, setError] = useState("");

  function handleSubmit(event) {
    event.preventDefault();
    const normalized = normalizeRepoUrl(url);
    if (!normalized) {
      setError("Enter a valid public GitHub repository URL (https://github.com/owner/repository).");
      return;
    }
    setError("");
    onSubmit(normalized);
  }

  return (
    <form className="repo-form" onSubmit={handleSubmit}>
      <label htmlFor="repo-url">GitHub repository URL</label>
      <div className="repo-input-row">
        <div className="input-with-icon"><Github size={19} /><input id="repo-url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://github.com/owner/repository" /></div>
        <button className="button button-primary" type="submit" disabled={loading}>{loading ? "Analyzing…" : buttonText}<ArrowRight size={16} /></button>
      </div>
      {error && <p className="form-error">{error}</p>}
      <p className="field-hint">Use a repository you own or are authorized to analyze. Private repository support requires backend authentication.</p>
    </form>
  );
}