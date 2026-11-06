#!/usr/bin/env python3
"""Load an audited SQLite JSONL export into PostgreSQL's isolated staging schema.

This does not map rows into live Imob-Pro domain tables. It preserves the source
records for deterministic reconciliation before a separately approved cutover.
Requires psycopg 3: pip install 'psycopg[binary]>=3.2,<4'.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import uuid
from pathlib import Path
from typing import Any

TABLES = {
    "users", "properties", "property_images", "leads", "visits", "favorites",
    "saved_searches", "collections", "collection_items", "tasks", "notifications",
    "activity_log", "settings", "property_views", "saved_search_alerts", "messages",
    "site_pages", "privacy_requests", "automation_rules",
}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def load_manifest(root: Path) -> tuple[dict[str, Any], str]:
    path = root / "manifest.json"
    manifest_hash = digest(path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("format_version") != 1:
        raise ValueError("Unsupported export format_version")
    tables = manifest.get("tables", {})
    if set(tables) != TABLES:
        raise ValueError("Export table set differs from the audited allowlist")
    for table, details in tables.items():
        file_path = root / details["file"]
        if file_path.parent != root or not file_path.is_file():
            raise ValueError(f"Invalid export path for {table}")
        if digest(file_path) != details["sha256"]:
            raise ValueError(f"SHA-256 mismatch for {table}")
        actual = sum(1 for _ in file_path.open("r", encoding="utf-8"))
        if actual != details["rows"]:
            raise ValueError(f"Row count mismatch for {table}: expected {details['rows']}, got {actual}")
    return manifest, manifest_hash


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", required=True, type=Path, help="Directory produced by export_sqlite.py")
    parser.add_argument("--dsn", required=True, help="PostgreSQL DSN; use a restricted staging database")
    args = parser.parse_args()

    root = args.export.resolve()
    manifest, manifest_hash = load_manifest(root)
    try:
        import psycopg
        from psycopg.types.json import Jsonb
    except ImportError as error:
        raise RuntimeError("Install psycopg 3: pip install 'psycopg[binary]>=3.2,<4'") from error

    source_hash = manifest["source_database_sha256"]
    batch_id = uuid.uuid5(uuid.NAMESPACE_URL, f"imobpro-legacy-export:{manifest_hash}")
    expected_total = sum(item["rows"] for item in manifest["tables"].values())
    imported_total = 0

    with psycopg.connect(args.dsn) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO legacy_import_batches
                    (id, source_database_sha256, manifest_sha256, source_table_count)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (id) DO NOTHING
                """,
                (batch_id, source_hash, manifest_hash, len(TABLES)),
            )
            cursor.execute(
                "SELECT source_database_sha256, manifest_sha256 FROM legacy_import_batches WHERE id = %s FOR UPDATE",
                (batch_id,),
            )
            existing = cursor.fetchone()
            if not existing or existing[0].strip() != source_hash or existing[1].strip() != manifest_hash:
                raise RuntimeError("Existing batch identity does not match export manifest")

            for table in sorted(TABLES):
                path = root / manifest["tables"][table]["file"]
                table_count = 0
                with path.open("r", encoding="utf-8") as stream:
                    for line_number, line in enumerate(stream, start=1):
                        row = json.loads(line)
                        if not isinstance(row, dict):
                            raise ValueError(f"{table}:{line_number}: expected a JSON object")
                        canonical = json.dumps(row, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
                        row_hash = hashlib.sha256(canonical.encode()).hexdigest()
                        primary_key = manifest["tables"][table].get("primary_key", [])
                        if primary_key and all(row.get(key) is not None for key in primary_key):
                            key_values = [row[key] for key in primary_key]
                            legacy_id = json.dumps(key_values, ensure_ascii=False, separators=(",", ":"))
                        elif "id" in row and row["id"] is not None:
                            legacy_id = str(row["id"])
                        else:
                            # Preserve keyless/composite-key rows without silently collapsing duplicates.
                            legacy_id = f"row:{line_number}:{row_hash}"
                        cursor.execute(
                            """
                            INSERT INTO legacy_import_rows
                                (batch_id, source_table, legacy_id, row_data, row_sha256)
                            VALUES (%s, %s, %s, %s, %s)
                            ON CONFLICT (batch_id, source_table, legacy_id) DO NOTHING
                            """,
                            (batch_id, table, legacy_id, Jsonb(row), row_hash),
                        )
                        cursor.execute(
                            """
                            SELECT row_sha256 FROM legacy_import_rows
                            WHERE batch_id = %s AND source_table = %s AND legacy_id = %s
                            """,
                            (batch_id, table, legacy_id),
                        )
                        saved_hash = cursor.fetchone()[0].strip()
                        if saved_hash != row_hash:
                            raise RuntimeError(f"Conflicting row on retry: {table}:{legacy_id}")
                        table_count += 1
                if table_count != manifest["tables"][table]["rows"]:
                    raise RuntimeError(f"Reconciliation failed for {table}")
                imported_total += table_count

            cursor.execute(
                """
                UPDATE legacy_import_batches
                SET status = 'RECONCILED', imported_row_count = %s, completed_at = NOW()
                WHERE id = %s
                """,
                (imported_total, batch_id),
            )
            cursor.execute(
                """
                SELECT source_table, COUNT(*)
                FROM legacy_import_rows WHERE batch_id = %s
                GROUP BY source_table ORDER BY source_table
                """,
                (batch_id,),
            )
            actual_counts = {name: count for name, count in cursor.fetchall()}
            expected_counts = {name: data["rows"] for name, data in manifest["tables"].items()}
            if actual_counts != expected_counts or imported_total != expected_total:
                raise RuntimeError("Post-load row-count reconciliation failed")

    print(json.dumps({
        "status": "reconciled",
        "batch_id": str(batch_id),
        "tables": len(TABLES),
        "rows": imported_total,
        "manifest_sha256": manifest_hash,
        "note": "Rows are in legacy_import_rows staging only; live domain tables were not modified.",
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
