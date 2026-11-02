from app.security import redact_secrets, scan_security
from tests.helpers import make_file


def titles(findings):
    return [f.title for f in findings]


def test_detects_hardcoded_aws_key_as_high_with_line_number():
    src = "import os\n\nKEY = 'AKIAIOSFODNN7EXAMPLE'\n"
    findings = scan_security([make_file("config.py", src)])
    hit = [f for f in findings if "secret" in f.title.lower()]
    assert len(hit) == 1
    assert hit[0].severity == "High"
    assert hit[0].file == "config.py"
    assert hit[0].line == 3


def test_detects_hardcoded_password_assignment():
    findings = scan_security([make_file("db.py", "password = 'hunter2hunter2'\n")])
    assert any("secret" in t.lower() for t in titles(findings))


def test_secret_detail_never_echoes_the_secret_value():
    findings = scan_security([make_file("c.py", "token = 'ghp_abcdefghijklmnopqrstuvwxyz0123456789'\n")])
    assert findings
    assert all("ghp_abcdefghijklmnopqrstuvwxyz0123456789" not in f.detail for f in findings)
    assert all("ghp_abcdefghijklmnopqrstuvwxyz0123456789" not in f.title for f in findings)


def test_ignores_placeholder_values_in_env_example():
    findings = scan_security([make_file(".env.example", "API_KEY=your-api-key-here\nPASSWORD=changeme\n")])
    assert findings == []


def test_detects_python_eval_and_shell_true():
    src = "import subprocess\nx = eval(user)\nsubprocess.run(cmd, shell=True)\n"
    findings = scan_security([make_file("run.py", src)])
    assert any("eval" in t.lower() for t in titles(findings))
    assert any("shell" in t.lower() for t in titles(findings))


def test_detects_sql_built_with_string_concatenation():
    src = 'cur.execute("SELECT * FROM users WHERE id = " + user_id)\n'
    findings = scan_security([make_file("dao.py", src)])
    assert any("sql" in t.lower() for t in titles(findings))


def test_detects_sql_built_with_fstring():
    src = 'cur.execute(f"SELECT * FROM users WHERE id = {user_id}")\n'
    findings = scan_security([make_file("dao.py", src)])
    assert any("sql" in t.lower() for t in titles(findings))


def test_detects_weak_hash_and_disabled_tls_verification():
    src = "h = hashlib.md5(data)\nrequests.get(url, verify=False)\n"
    findings = scan_security([make_file("net.py", src)])
    assert any("weak hash" in t.lower() for t in titles(findings))
    assert any("tls" in t.lower() for t in titles(findings))


def test_detects_java_runtime_exec_and_md5():
    src = 'Runtime.getRuntime().exec(cmd);\nMessageDigest.getInstance("MD5");\n'
    findings = scan_security([make_file("A.java", src)])
    assert any("command" in t.lower() or "exec" in t.lower() for t in titles(findings))
    assert any("weak hash" in t.lower() for t in titles(findings))


def test_clean_code_has_no_findings():
    src = "def add(a, b):\n    return a + b\n"
    assert scan_security([make_file("m.py", src)]) == []


def test_findings_sorted_high_first():
    src = "h = hashlib.md5(x)\nKEY = 'AKIAIOSFODNN7EXAMPLE'\n"
    findings = scan_security([make_file("a.py", src)])
    order = {"High": 0, "Medium": 1, "Low": 2, "Info": 3}
    ranks = [order[f.severity] for f in findings]
    assert ranks == sorted(ranks)


def test_detects_sql_concat_when_string_contains_quotes_inside():
    src = "cur.execute(\"SELECT * FROM users WHERE name = '\" + name + \"'\")\n"
    findings = scan_security([make_file("dao.py", src)])
    assert any("sql" in t.lower() for t in titles(findings))


def test_detects_sql_percent_formatting_and_single_quoted_strings():
    findings = scan_security([make_file("a.py", "cur.execute('DELETE FROM t WHERE id = %s' % uid)\n")])
    assert any("sql" in t.lower() for t in titles(findings))


def test_parameterized_sql_is_not_flagged():
    src = "cur.execute(\"SELECT * FROM users WHERE name = %s\", (name,))\n"
    assert scan_security([make_file("dao.py", src)]) == []


def test_redact_secrets_removes_known_token_shapes_and_assigned_values():
    text = "KEY = 'AKIAIOSFODNN7EXAMPLE'\npassword = 'hunter2hunter2'\ntoken: ghp_abcdefghijklmnopqrstuvwxyz0123456789\n"
    out = redact_secrets(text)
    assert "AKIAIOSFODNN7EXAMPLE" not in out
    assert "hunter2hunter2" not in out
    assert "ghp_abcdefghijklmnopqrstuvwxyz0123456789" not in out
    assert "[REDACTED]" in out
    assert "password" in out  # the variable name stays so the code is still readable


def test_redact_secrets_leaves_ordinary_code_alone():
    code = "def add(a, b):\n    return a + b\n"
    assert redact_secrets(code) == code
