#!/usr/bin/env python3
"""Validate local markdown links and docs package README coverage."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCS_ROOT = REPO_ROOT / "docs"
LOCAL_DOC_ROOTS = [REPO_ROOT / "docs", REPO_ROOT / "README.md", REPO_ROOT / "README.ko.md"]
LINK_PATTERN = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
SKIP_SCHEMES = ("http://", "https://", "mailto:", "tel:", "data:")


def iter_markdown_files() -> Iterable[Path]:
    for root in LOCAL_DOC_ROOTS:
        if root.is_file():
            yield root
            continue
        for path in root.rglob("*.md"):
            yield path


def normalize_link_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    if " " in target:
        # Support markdown title syntax: (path "title")
        target = target.split(" ", 1)[0]
    return target


def resolve_local_target(source: Path, raw_target: str) -> Path | None:
    target = normalize_link_target(raw_target)
    if not target:
        return None
    if target.startswith(SKIP_SCHEMES) or target.startswith("#"):
        return None

    path_only = target.split("#", 1)[0].strip()
    if not path_only:
        return None

    if path_only.startswith("/"):
        resolved = (REPO_ROOT / path_only.lstrip("/")).resolve()
    else:
        resolved = (source.parent / path_only).resolve()
    return resolved


def check_local_links(files: Iterable[Path]) -> list[str]:
    errors: list[str] = []
    for file_path in files:
        text = file_path.read_text(encoding="utf-8")
        for match in LINK_PATTERN.finditer(text):
            raw_target = match.group(1)
            resolved = resolve_local_target(file_path, raw_target)
            if resolved is None:
                continue
            if not resolved.exists():
                rel_file = file_path.relative_to(REPO_ROOT)
                rel_target = raw_target.strip()
                errors.append(
                    f"::error file={rel_file}::broken local link '{rel_target}' (resolved: {resolved.relative_to(REPO_ROOT) if resolved.is_relative_to(REPO_ROOT) else resolved})"
                )
    return errors


def parse_readme_link_targets(readme: Path) -> set[Path]:
    text = readme.read_text(encoding="utf-8")
    targets: set[Path] = set()
    for match in LINK_PATTERN.finditer(text):
        resolved = resolve_local_target(readme, match.group(1))
        if resolved is None:
            continue
        if resolved.suffix.lower() != ".md":
            continue
        if resolved.exists():
            targets.add(resolved)
    return targets


def check_docs_package_readmes() -> list[str]:
    errors: list[str] = []
    for lang in ("en", "ko"):
        lang_root = DOCS_ROOT / lang
        for readme in lang_root.rglob("README.md"):
            siblings = sorted(
                p for p in readme.parent.glob("*.md") if p.name != "README.md"
            )
            if not siblings:
                continue
            linked = parse_readme_link_targets(readme)
            for sibling in siblings:
                if sibling not in linked:
                    rel_readme = readme.relative_to(REPO_ROOT)
                    rel_sibling = sibling.relative_to(REPO_ROOT)
                    errors.append(
                        f"::error file={rel_readme}::missing package index entry for '{rel_sibling.name}'"
                    )
    return errors


def main() -> int:
    link_errors = check_local_links(iter_markdown_files())
    index_errors = check_docs_package_readmes()
    all_errors = link_errors + index_errors

    if all_errors:
        for error in all_errors:
            print(error)
        print(f"Doc checks failed: {len(all_errors)} issue(s).", file=sys.stderr)
        return 1

    print("Doc checks passed: links valid and package indexes complete.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
