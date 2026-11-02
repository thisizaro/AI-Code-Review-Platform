import re

from app.models import SEVERITY_ORDER, Finding, RepoFile

CODE_EXTENSIONS = {"py", "java", "js", "jsx", "ts", "tsx", "go", "rb", "php", "cs", "kt"}
DOC_EXTENSIONS = {"md", "txt", "rst"}
ENV_LIKE = re.compile(r"(\.env|\.properties|\.ya?ml|\.ini|\.cfg)(\.\w+)?$", re.I)
PLACEHOLDER = re.compile(r"your|change.?me|example|placeholder|dummy|sample|xxx|<.*>|\$\{|\{\{|todo|password$|secret$", re.I)

SECRET_PATTERNS = [
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"),
]
ASSIGN_QUOTED = re.compile(r"""(?i)(password|passwd|secret|api[_-]?key|token)\w*\s*[:=]\s*['"]([^'"\s]{8,})['"]""")
ASSIGN_BARE = re.compile(r"(?i)^\s*\w*(password|passwd|secret|api[_-]?key|token)\w*\s*[:=]\s*([^\s'\"#]{8,})\s*$")

SQL_WORDS = r"\b(select|insert|update|delete)\b"
CALLS = r"(execute|executemany|query|prepareStatement|createQuery|raw)"
SQL_CONCAT = re.compile(rf"""(?i){CALLS}\s*\(\s*(?:"[^"]*{SQL_WORDS}[^"]*"|'[^']*{SQL_WORDS}[^']*')\s*(\+|%)""")
SQL_FSTRING = re.compile(rf"""(?i){CALLS}\s*\(\s*f(?:"[^"]*{SQL_WORDS}[^"]*\{{|'[^']*{SQL_WORDS}[^']*\{{)""")

# (title, severity, detail, compiled pattern)
CODE_RULES = [
    ("Use of eval/exec", "High", "Dynamic code evaluation can run attacker-controlled input. Parse data explicitly instead.", re.compile(r"(?<![\w.])(eval|exec)\(")),
    ("Shell command with shell=True", "High", "Passing a command string with shell=True enables shell injection. Pass an argument list instead.", re.compile(r"shell\s*=\s*True")),
    ("OS command execution", "Medium", "Executing OS commands from code is risky if any part comes from user input. Validate inputs or avoid it.", re.compile(r"Runtime\.getRuntime\(\)\.exec\(")),
    ("SQL built with string concatenation", "High", "Building SQL from strings allows SQL injection. Use parameterized queries.", SQL_CONCAT),
    ("SQL built with string concatenation", "High", "Building SQL from strings allows SQL injection. Use parameterized queries.", SQL_FSTRING),
    ("Weak hash algorithm", "Medium", "MD5 and SHA-1 are broken for security purposes. Use SHA-256 or stronger (bcrypt/argon2 for passwords).", re.compile(r"""hashlib\.(md5|sha1)\(|MessageDigest\.getInstance\(\s*["'](MD5|SHA-?1)["']|createHash\(\s*["'](md5|sha1)["']""", re.I)),
    ("TLS verification disabled", "Medium", "Disabling certificate verification exposes connections to interception. Keep verification on.", re.compile(r"verify\s*=\s*False|rejectUnauthorized\s*:\s*false|InsecureSkipVerify|TrustAllCerts", re.I)),
]


def _ext(path: str) -> str:
    return path.rsplit(".", 1)[-1].lower() if "." in path.rsplit("/", 1)[-1] else ""


def _is_placeholder(value: str) -> bool:
    return bool(PLACEHOLDER.search(value))


def _secret_hit(path: str, line: str) -> bool:
    if any(p.search(line) for p in SECRET_PATTERNS):
        return True
    for m in ASSIGN_QUOTED.finditer(line):
        if not _is_placeholder(m.group(2)):
            return True
    if ENV_LIKE.search(path):
        m = ASSIGN_BARE.match(line)
        if m and not _is_placeholder(m.group(2)):
            return True
    return False


def scan_security(files: list[RepoFile]) -> list[Finding]:
    findings: list[Finding] = []
    for f in files:
        ext = _ext(f.path)
        lines = f.content.splitlines()
        if ext not in DOC_EXTENSIONS:
            for i, line in enumerate(lines, 1):
                if _secret_hit(f.path, line):
                    findings.append(Finding(
                        severity="High", title="Hardcoded secret", file=f.path, line=i,
                        detail="A credential-like value is committed in source. Move it to an environment variable and rotate it.",
                    ))
        if ext in CODE_EXTENSIONS:
            for i, line in enumerate(lines, 1):
                seen: set[str] = set()
                for title, severity, detail, pattern in CODE_RULES:
                    if title not in seen and pattern.search(line):
                        seen.add(title)
                        findings.append(Finding(severity=severity, title=title, file=f.path, line=i, detail=detail))
    findings.sort(key=lambda x: SEVERITY_ORDER[x.severity])
    return findings


def redact_secrets(text: str, path: str = "") -> str:
    """Replace secret-looking values with [REDACTED] so snippets are safe to show or send to an LLM."""
    for pattern in SECRET_PATTERNS:
        text = pattern.sub("[REDACTED]", text)

    def quoted(m: re.Match) -> str:
        return m.group(0) if _is_placeholder(m.group(2)) else m.group(0).replace(m.group(2), "[REDACTED]")

    text = ASSIGN_QUOTED.sub(quoted, text)
    if ENV_LIKE.search(path):
        lines = []
        for line in text.splitlines(keepends=True):
            m = ASSIGN_BARE.match(line.rstrip("\r\n"))
            if m and not _is_placeholder(m.group(2)) and m.group(2) != "[REDACTED]":
                line = line.replace(m.group(2), "[REDACTED]")
            lines.append(line)
        text = "".join(lines)
    return text
