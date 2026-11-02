import ast
import re

from app.models import SEVERITY_ORDER, Finding, RepoFile

CODE_EXTENSIONS = {"py", "java", "js", "jsx", "ts", "tsx", "go", "rb", "php", "cs", "kt"}
MAX_FUNCTION_LINES = 50
MAX_NESTING = 4
MAX_COMPLEXITY = 10
MAX_FILE_LINES = 800
TODO_LIMIT = 5

JAVA_METHOD = re.compile(r"^\s*(?:(?:public|private|protected|static|final|synchronized|abstract)\s+)+[\w<>\[\],.? ]+\s+\w+\s*\([^;{)]*\)\s*(?:throws [\w., ]+)?\s*\{")
JS_FUNCTION = re.compile(r"\bfunction\s+\w+\s*\(|\b\w+\s*=\s*(?:async\s*)?\([^)]*\)\s*=>|\b\w+\s*=\s*(?:async\s*)?function\b")
GO_FUNCTION = re.compile(r"^func\s")
EMPTY_CATCH = re.compile(r"catch\s*(\([^)]*\))?\s*\{\s*\}")
TODO = re.compile(r"\b(TODO|FIXME|HACK|XXX)\b")

NESTING_NODES = (ast.If, ast.For, ast.AsyncFor, ast.While, ast.Try, ast.With, ast.AsyncWith)
FUNCTION_NODES = (ast.FunctionDef, ast.AsyncFunctionDef)


def _ext(path: str) -> str:
    return path.rsplit(".", 1)[-1].lower() if "." in path.rsplit("/", 1)[-1] else ""


def _max_nesting(node: ast.AST, depth: int = 0) -> int:
    best = depth
    for child in ast.iter_child_nodes(node):
        if isinstance(child, FUNCTION_NODES):
            continue
        next_depth = depth + 1 if isinstance(child, NESTING_NODES) else depth
        best = max(best, _max_nesting(child, next_depth))
    return best


def _complexity(func: ast.AST) -> int:
    total = 1
    for node in ast.walk(func):
        if isinstance(node, (ast.If, ast.For, ast.AsyncFor, ast.While, ast.ExceptHandler, ast.IfExp)):
            total += 1
        elif isinstance(node, ast.BoolOp):
            total += len(node.values) - 1
        elif isinstance(node, ast.comprehension):
            total += len(node.ifs)
    return total


def _has_mutable_default(func: ast.AST) -> bool:
    args = func.args
    for d in [*args.defaults, *[k for k in args.kw_defaults if k is not None]]:
        if isinstance(d, (ast.List, ast.Dict, ast.Set)):
            return True
        if isinstance(d, ast.Call) and isinstance(d.func, ast.Name) and d.func.id in {"list", "dict", "set"}:
            return True
    return False


def _analyze_python(f: RepoFile) -> tuple[list[Finding], int]:
    try:
        tree = ast.parse(f.content)
    except SyntaxError:
        return [Finding(severity="Info", title="Could not parse file", file=f.path, line=None,
                        detail="The file has a syntax error or uses syntax this analyzer does not support, so it was skipped.")], 0
    issues: list[Finding] = []
    count = 0
    for node in ast.walk(tree):
        if isinstance(node, FUNCTION_NODES):
            count += 1
            length = (node.end_lineno or node.lineno) - node.lineno + 1
            if length > MAX_FUNCTION_LINES:
                issues.append(Finding(severity="Medium", title=f"Long function: {node.name} ({length} lines)", file=f.path, line=node.lineno,
                                      detail=f"Functions over {MAX_FUNCTION_LINES} lines are hard to read and test. Split it into smaller functions."))
            if _max_nesting(node) > MAX_NESTING:
                issues.append(Finding(severity="Medium", title=f"Deep nesting in {node.name}", file=f.path, line=node.lineno,
                                      detail=f"Control flow is nested more than {MAX_NESTING} levels. Use early returns or helper functions."))
            if _complexity(node) > MAX_COMPLEXITY:
                issues.append(Finding(severity="Medium", title=f"Complex function: {node.name}", file=f.path, line=node.lineno,
                                      detail=f"Many branches (complexity above {MAX_COMPLEXITY}). Extract conditions into smaller functions."))
            if _has_mutable_default(node):
                issues.append(Finding(severity="Medium", title=f"Mutable default argument in {node.name}", file=f.path, line=node.lineno,
                                      detail="Default list/dict/set values are shared between calls. Use None and create the value inside the function."))
        elif isinstance(node, ast.ExceptHandler) and node.type is None:
            issues.append(Finding(severity="Low", title="Bare except", file=f.path, line=node.lineno,
                                  detail="A bare except also catches KeyboardInterrupt and hides bugs. Catch specific exceptions."))
    return issues, count


def _count_other(ext: str, content: str) -> int:
    pattern = {"java": JAVA_METHOD, "kt": None, "go": GO_FUNCTION}.get(ext, JS_FUNCTION if ext in {"js", "jsx", "ts", "tsx"} else None)
    if pattern is None:
        return 0
    return sum(1 for line in content.splitlines() if pattern.search(line))


def analyze_static(files: list[RepoFile]) -> tuple[list[Finding], int]:
    issues: list[Finding] = []
    functions = 0
    for f in files:
        ext = _ext(f.path)
        if ext not in CODE_EXTENSIONS:
            continue
        if ext == "py":
            found, count = _analyze_python(f)
            issues.extend(found)
            functions += count
        else:
            functions += _count_other(ext, f.content)
        lines = f.content.count("\n") + 1
        if lines > MAX_FILE_LINES:
            issues.append(Finding(severity="Low", title=f"Large file ({lines} lines)", file=f.path, line=None,
                                  detail="Very large files are hard to navigate. Consider splitting by responsibility."))
        m = EMPTY_CATCH.search(f.content)
        if m and ext != "py":
            issues.append(Finding(severity="Medium", title="Empty catch block", file=f.path, line=f.content.count("\n", 0, m.start()) + 1,
                                  detail="Swallowing exceptions hides failures. Log or handle the error."))
        todos = len(TODO.findall(f.content))
        if todos >= TODO_LIMIT:
            issues.append(Finding(severity="Low", title=f"Many TODO/FIXME comments ({todos})", file=f.path, line=None,
                                  detail="Unfinished work is piling up in this file. Track it in an issue tracker or resolve it."))
    issues.sort(key=lambda x: SEVERITY_ORDER[x.severity])
    return issues, functions
