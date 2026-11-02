import { useState } from "react";
import { useApp } from "../context/AppContext";
import { analyzeJobMatch } from "../services/api";
import { normalizeRepoUrl } from "../utils/repoUrl";
import PageHeader from "../components/PageHeader";
import ScoreRing from "../components/ScoreRing";
import { BriefcaseBusiness, Sparkles, AlertCircle } from "lucide-react";

export default function JDMatching() {
  const { repositoryUrl, setRepositoryUrl, matchingResult, setMatchingResult } = useApp();
  const [url, setUrl] = useState(repositoryUrl);
  const [description, setDescription] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    const repo = normalizeRepoUrl(url);
    if (!repo || !description.trim()) { setError("Provide a valid GitHub repository URL and a job description."); return; }
    setLoading(true); setError(""); setMatchingResult(null); setRepositoryUrl(repo);
    try { setMatchingResult(await analyzeJobMatch(repo, description.trim())); }
    catch (err) { setError(err.message); }
    finally { setLoading(false); }
  }

  const result = matchingResult;
  return <>
    <PageHeader eyebrow="CANDIDATE INTELLIGENCE" title="Job description matching" description="Compare skills evidenced in a repository with the requirements of a specific role." />
    <section className="panel">
      <div className="section-heading"><div><h2>Matching inputs</h2><p>Provide a repository and the target job description.</p></div><span className="soft-tag">JOB-SPECIFIC</span></div>
      <form onSubmit={submit} className="stack-form">
        <div className="form-field"><label htmlFor="match-repo">GitHub repository URL</label><input id="match-repo" value={url} onChange={e => setUrl(e.target.value)} placeholder="https://github.com/owner/repository" /></div>
        <div className="form-field"><label htmlFor="job-description">Job description</label><textarea id="job-description" rows="7" value={description} onChange={e => setDescription(e.target.value)} placeholder="Paste the job responsibilities, required skills, and qualifications…" /></div>
        {error && <div className="notice notice-warning"><AlertCircle size={17} />{error}</div>}
        <div><button className="button button-primary" disabled={loading}>{loading ? "Evaluating…" : "Evaluate match"} <Sparkles size={16} /></button></div>
      </form>
    </section>
    {loading && <div className="loading-panel"><div className="spinner"></div><strong>Evaluating repository evidence…</strong></div>}
    {result && !loading && <section className="panel">
      <div className="section-heading"><div><h2>Match assessment</h2><p>Evidence-based alignment with the supplied job description.</p></div></div>
      <div className="match-overview"><ScoreRing score={result.match_score ?? result.score ?? 0} label="Match" size={132} /><div><h3>Repository-to-role alignment</h3><p>{result.explanation || result.summary || "Review the matched and missing skills as repository evidence, not as a complete assessment of the candidate."}</p></div></div>
      <div className="skill-columns"><SkillList title="Skills evidenced" values={result.matched_skills || []} kind="matched" /><SkillList title="Skills not evidenced" values={result.missing_skills || []} kind="missing" /></div>
      <div className="notice notice-info"><BriefcaseBusiness size={17} />This score is an assistive signal, not a hiring decision. Repository evidence is incomplete and should be reviewed alongside interviews and other relevant information.</div>
    </section>}
  </>;
}

function SkillList({ title, values, kind }) {
  return <div className="skill-box"><h3>{title} <span>{values.length}</span></h3>{values.length ? <div className="skill-tags">{values.map(skill => <span key={skill} className={`skill-tag ${kind}`}>{skill}</span>)}</div> : <p className="muted">No skills returned.</p>}</div>;
}