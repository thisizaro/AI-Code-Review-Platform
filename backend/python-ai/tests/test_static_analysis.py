from app.static_analysis import analyze_static
from tests.helpers import make_file


def titles(issues):
    return [i.title.lower() for i in issues]


def test_counts_python_functions_including_methods_and_async():
    src = "def a():\n    pass\n\nclass C:\n    def b(self):\n        pass\n\nasync def c():\n    pass\n"
    _, count = analyze_static([make_file("m.py", src)])
    assert count == 3


def test_counts_java_methods():
    src = "public class A {\n  public void run() {\n  }\n  private int add(int a, int b) {\n    return a + b;\n  }\n}\n"
    _, count = analyze_static([make_file("A.java", src)])
    assert count == 2


def test_flags_long_python_function():
    body = "".join(f"    x{i} = {i}\n" for i in range(70))
    issues, _ = analyze_static([make_file("m.py", "def big():\n" + body)])
    hit = [i for i in issues if "long function" in i.title.lower()]
    assert len(hit) == 1
    assert hit[0].file == "m.py"
    assert hit[0].line == 1


def test_flags_deep_nesting():
    src = (
        "def f(a):\n"
        "    if a:\n"
        "        for i in a:\n"
        "            while i:\n"
        "                if i > 1:\n"
        "                    if i > 2:\n"
        "                        return 1\n"
    )
    issues, _ = analyze_static([make_file("m.py", src)])
    assert any("nesting" in t for t in titles(issues))


def test_flags_bare_except_and_mutable_default():
    src = "def f(x=[]):\n    try:\n        pass\n    except:\n        pass\n"
    issues, _ = analyze_static([make_file("m.py", src)])
    assert any("bare except" in t for t in titles(issues))
    assert any("mutable default" in t for t in titles(issues))


def test_flags_high_branch_count():
    branches = "".join(f"    if a == {i}:\n        return {i}\n" for i in range(14))
    issues, _ = analyze_static([make_file("m.py", "def f(a):\n" + branches)])
    assert any("complex" in t for t in titles(issues))


def test_python_syntax_error_does_not_crash_and_is_reported_low():
    issues, count = analyze_static([make_file("bad.py", "def f(:\n  pass\n")])
    assert count == 0
    assert any("could not parse" in t for t in titles(issues))
    assert all(i.severity in ("Low", "Info") for i in issues)


def test_flags_empty_catch_block_in_java_and_js():
    java = "try { run(); } catch (Exception e) { }\n"
    js = "try { run(); } catch (e) {}\n"
    issues, _ = analyze_static([make_file("A.java", java), make_file("a.js", js)])
    files = {i.file for i in issues if "empty catch" in i.title.lower()}
    assert files == {"A.java", "a.js"}


def test_flags_very_large_file():
    src = "x = 1\n" * 900
    issues, _ = analyze_static([make_file("big.py", src)])
    assert any("large file" in t for t in titles(issues))


def test_flags_todo_density():
    src = "".join(f"# TODO fix {i}\nx = {i}\n" for i in range(8))
    issues, _ = analyze_static([make_file("t.py", src)])
    assert any("todo" in t for t in titles(issues))


def test_clean_python_has_no_issues():
    src = "def add(a, b):\n    return a + b\n"
    issues, count = analyze_static([make_file("m.py", src)])
    assert issues == []
    assert count == 1


def test_skips_non_code_files():
    issues, count = analyze_static([make_file("README.md", "# TODO\n" * 20)])
    assert issues == [] and count == 0
