import { useState } from "react";
import { useApp } from "../context/AppContext";
import { askProjectQuestion } from "../services/api";
import { normalizeRepoUrl } from "../utils/repoUrl";
import PageHeader from "../components/PageHeader";
import { Bot, Send, UserRound, BookOpen, AlertCircle } from "lucide-react";

export default function ProjectLearning() {
  const { repositoryUrl, setRepositoryUrl } = useApp();
  const [repo, setRepo] = useState(repositoryUrl);
  const [question, setQuestion] = useState("");
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    const repoUrl = normalizeRepoUrl(repo);
    if (!repoUrl || !question.trim()) { setError("Enter a valid GitHub repository URL and a question."); return; }
    const prompt = question.trim();
    setMessages(prev => [...prev, { role: "user", content: prompt }]);
    setQuestion(""); setLoading(true); setError(""); setRepositoryUrl(repoUrl);
    try {
      const response = await askProjectQuestion(repoUrl, prompt);
      const answer = response.answer || response.response || response.message || JSON.stringify(response, null, 2);
      setMessages(prev => [...prev, { role: "assistant", content: answer, sources: response.sources || [] }]);
    } catch (err) {
      setError(err.message);
    } finally { setLoading(false); }
  }

  return <>
    <PageHeader eyebrow="DEVELOPER LEARNING" title="Project learning studio" description="Ask questions about a codebase and understand its structure, components, and implementation." />
    <section className="panel learning-panel">
      <div className="learning-intro"><div className="learning-icon"><BookOpen size={21} /></div><div><h2>Learn directly from a repository</h2><p>Answers should be grounded in retrieved source files and include file references when available.</p></div></div>
      <div className="form-field"><label htmlFor="learning-repo">GitHub repository URL</label><input id="learning-repo" value={repo} onChange={e => setRepo(e.target.value)} placeholder="https://github.com/owner/repository" /></div>
      <div className="chat-window">
        {messages.length === 0 ? <div className="chat-empty"><div className="bot-large"><Bot size={28} /></div><h3>What would you like to understand?</h3><p>Try asking about the project architecture, API flow, key classes, or how a feature works.</p><div className="suggestion-list">{["Explain the project architecture", "Where are the API routes defined?", "How does the main workflow work?"].map(q => <button key={q} onClick={() => setQuestion(q)}>{q}</button>)}</div></div> :
          messages.map((message, index) => <div key={index} className={`chat-message ${message.role}`}><div className="message-avatar">{message.role === "user" ? <UserRound size={16} /> : <Bot size={16} />}</div><div className="message-body"><span>{message.role === "user" ? "You" : "RepoMind assistant"}</span><p>{message.content}</p>{message.sources?.length > 0 && <div className="source-list">Sources: {message.sources.map((source, i) => <code key={i}>{typeof source === "string" ? source : source.file || source.path}</code>)}</div>}</div></div>)
        }
        {loading && <div className="chat-message assistant"><div className="message-avatar"><Bot size={16} /></div><div className="message-body"><span>RepoMind assistant</span><p>Reviewing repository context…</p></div></div>}
      </div>
      {error && <div className="notice notice-warning"><AlertCircle size={17} />{error}</div>}
      <form className="chat-input-row" onSubmit={submit}><input value={question} onChange={e => setQuestion(e.target.value)} placeholder="Ask a question about this repository…" /><button className="button button-primary" disabled={loading || !question.trim()} aria-label="Send question"><Send size={17} /></button></form>
      <p className="field-hint">Do not treat generated explanations as a substitute for reviewing the referenced source code.</p>
    </section>
  </>;
}