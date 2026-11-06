#!/usr/bin/env python3
"""Read-only, auditable export of the legacy Imob-Pro SQLite database.

The exporter never mutates the source database. It deliberately excludes live
sessions and password-reset tokens: users must authenticate again after cutover.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import math
import os
import shutil
import sqlite3
import sys
from pathlib import Path
from typing import Any

EXPECTED_TABLES = [
    "users", "properties", "property_images", "leads", "visits", "favorites",
    "saved_searches", "collections", "collection_items", "tasks", "notifications",
    "activity_log", "settings", "property_views", "saved_search_alerts", "messages",
    "site_pages", "privacy_requests", "automation_rules",
]
EXCLUDED_TABLES = {
    "sessions": "session cookies are invalidated at cutover; never migrate sessions",
    "password_reset_tokens": "reset tokens are invalidated at cutover; users request new links",
}


def encode(value: Any) -> Any:
    if isinstance(value, bytes):
        return {"$binary_base64": base64.b64encode(value).decode("ascii")}
    if isinstance(value, (dt.datetime, dt.date, dt.time)):
        return value.isoformat()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path, help="Path to imobpro.sqlite")
    parser.add_argument("--output", required=True, type=Path, help="New export directory")
    parser.add_argument("--uploads", type=Path, help="Optional legacy uploads directory")
    args = parser.parse_args()

    database = args.database.resolve()
    output = args.output.resolve()
    if not database.is_file():
        parser.error(f"database not found: {database}")
    if output.exists() and any(output.iterdir()):
        parser.error(f"output directory must be empty: {output}")

    output.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(output, 0o700)
    except OSError:
        pass

    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only = ON")
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("BEGIN")  # pin one consistent read snapshot for the whole export
    integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        raise RuntimeError(f"SQLite integrity_check failed: {integrity}")
    foreign_key_errors = [tuple(row) for row in connection.execute("PRAGMA foreign_key_check")]
    if foreign_key_errors:
        raise RuntimeError(f"SQLite foreign_key_check found {len(foreign_key_errors)} violation(s)")

    available = {
        row["name"]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }
    missing = sorted(set(EXPECTED_TABLES) - available)
    if missing:
        raise RuntimeError("Legacy schema differs from audited schema; missing tables: " + ", ".join(missing))

    table_manifest: dict[str, Any] = {}
    for table in EXPECTED_TABLES:
        destination = output / f"{table}.jsonl"
        count = 0
        table_info = list(connection.execute(f'PRAGMA table_info("{table}")'))
        columns = [row["name"] for row in table_info]
        primary_key = [row["name"] for row in sorted((row for row in table_info if row["pk"]), key=lambda row: row["pk"]) ]
        with destination.open("w", encoding="utf-8", newline="\n") as stream:
            for row in connection.execute(f'SELECT * FROM "{table}"'):
                record = {key: encode(row[key]) for key in columns}
                stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                count += 1
        try:
            os.chmod(destination, 0o600)
        except OSError:
            pass
        table_manifest[table] = {
            "rows": count,
            "columns": columns,
            "primary_key": primary_key,
            "file": destination.name,
            "sha256": sha256(destination),
        }

    copied_uploads = None
    if args.uploads:
        source_uploads = args.uploads.resolve()
        if not source_uploads.is_dir():
            raise RuntimeError(f"uploads directory not found: {source_uploads}")
        target_uploads = output / "uploads"
        shutil.copytree(source_uploads, target_uploads, symlinks=False)
        file_entries = []
        for path in sorted(p for p in target_uploads.rglob("*") if p.is_file()):
            file_entries.append({
                "path": path.relative_to(target_uploads).as_posix(),
                "size_bytes": path.stat().st_size,
                "sha256": sha256(path),
            })
        copied_uploads = {"file_count": len(file_entries), "files": file_entries}

    manifest = {
        "format_version": 1,
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source_database": database.name,
        "source_database_sha256": sha256(database),
        "source_sidecars": {
            suffix: {"sha256": sha256(Path(str(database) + suffix)), "size_bytes": Path(str(database) + suffix).stat().st_size}
            for suffix in ("-wal", "-shm")
            if Path(str(database) + suffix).is_file()
        },
        "sqlite_integrity_check": integrity,
        "sqlite_foreign_key_violations": 0,
        "tables": table_manifest,
        "excluded_tables": EXCLUDED_TABLES,
        "uploads": copied_uploads,
        "cutover_notes": [
            "This is an immutable export, not a PostgreSQL import.",
            "Do not import sessions or password reset tokens.",
            "Keep the original SQLite file and this export read-only until reconciliation and rollback are approved.",
            "Restrict access: user records contain password hashes and personal data.",
        ],
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        os.chmod(manifest_path, 0o600)
    except OSError:
        pass
    connection.close()
    print(json.dumps({
        "status": "exported",
        "output": str(output),
        "tables": len(table_manifest),
        "rows": sum(item["rows"] for item in table_manifest.values()),
        "manifest_sha256": sha256(manifest_path),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
