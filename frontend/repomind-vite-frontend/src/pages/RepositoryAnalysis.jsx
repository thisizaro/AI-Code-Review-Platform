import { useState } from "react";
import { useApp } from "../context/AppContext";
import { analyzeRepository } from "../services/api";
import PageHeader from "../components/PageHeader";
import RepositoryForm from "../components/RepositoryForm";
import ScoreRing from "../components/ScoreRing";
import EmptyState from "../components/EmptyState";
import { AlertTriangle, ShieldAlert, Code2, RefreshCw } from "lucide-react";

export default function RepositoryAnalysis() {
  const { repositoryUrl, setRepositoryUrl, analysisResult, setAnalysisResult } = useApp();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function runAnalysis(url) {
    setRepositoryUrl(url);
    setLoading(true); setError(""); setAnalysisResult(null);
    try {
      const result = await analyzeRepository(url);
      setAnalysisResult(result);
    } catch (err) {
      setError(err.message);
    } finally { setLoading(false); }
  }

  const result = analysisResult;
  return (
    <>
      <PageHeader eyebrow="CODE INTELLIGENCE" title="Repository analysis" description="Inspect maintainability, code quality, and security signals from an authorized GitHub repository." />
      <section className="panel">
        <div className="section-heading"><div><h2>Repository source</h2><p>Provide the GitHub URL you want to inspect.</p></div></div>
        <RepositoryForm onSubmit={runAnalysis} loading={loading} buttonText="Run analysis" />
        {repositoryUrl && <p className="selected-repo">Selected: <strong>{repositoryUrl}</strong></p>}
        {error && <div className="notice notice-warning"><AlertTriangle size={17} />{error}</div>}
      </section>
      {loading && <div className="loading-panel"><div className="spinner"></div><strong>Analyzing repository…</strong><span>Collecting code metrics and review findings.</span></div>}
      {result && !loading && <>
        <div className="result-heading"><div><h2>Analysis results</h2><p>Review the findings below and validate them against the source code.</p></div></div>
        {result.metrics?.truncated && <div className="notice notice-warning"><AlertTriangle size={17} />This repository is large, so only part of it was analyzed (file size and count limits apply).</div>}
        <section className="analysis-summary">
          <div className="panel score-panel"><ScoreRing score={result.quality_score ?? result.qualityScore ?? 0} label="Quality" size={132} /><div><h3>Code quality</h3><p>Composite score returned by the analysis service. Confirm its calculation and scoring rubric in the backend.</p></div></div>
          <div className="panel mini-metrics">
            <div><span>Files analyzed</span><strong>{result.metrics?.files_analyzed ?? "—"}</strong></div>
            <div><span>Functions analyzed</span><strong>{result.metrics?.functions_analyzed ?? "—"}</strong></div>
            <div><span>Test files</span><strong>{result.metrics?.test_files ?? "—"}</strong></div>
          </div>
        </section>
        <section className="panel">
          <div className="section-heading"><div><h2><Code2 size={18} /> Code quality findings</h2><p>Potential maintainability and correctness issues.</p></div><span className="count-tag">{result.issues?.length ?? 0} findings</span></div>
          {result.issues?.length ? <div className="finding-list">{result.issues.map((item, index) => <Finding key={index} item={item} />)}</div> : <EmptyState title="No code findings returned" description="The analysis service did not return code quality findings." />}
        </section>
        <section className="panel">
          <div className="section-heading"><div><h2><ShieldAlert size={18} /> Security findings</h2><p>Potential security concerns requiring developer review.</p></div><span className="count-tag">{result.security_findings?.length ?? 0} findings</span></div>
          {result.security_findings?.length ? <div className="finding-list">{result.security_findings.map((item, index) => <Finding key={index} item={item} />)}</div> : <EmptyState title="No security findings returned" description="The security scan did not return findings." />}
        </section>
      </>}
      {!result && !loading && <div className="panel"><EmptyState title="Ready to inspect your code" description="Enter a GitHub repository URL above. Results will appear here after analysis." /></div>}
    </>
  );
}

function Finding({ item }) {
  const severity = (item.severity || "Info").toLowerCase();
  return <article className="finding-item"><div className={`severity-dot ${severity}`}></div><div className="finding-content"><div className="finding-title-row"><strong>{item.title || item.message || "Finding"}</strong><span className={`severity-label ${severity}`}>{item.severity || "Info"}</span></div><code>{item.file || item.path || "File not specified"}</code><p>{item.detail || item.description || "No additional explanation provided."}</p></div></article>;
}