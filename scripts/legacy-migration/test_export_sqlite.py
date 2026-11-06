import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

EXPORTER = Path(__file__).with_name("export_sqlite.py")
TABLES = [
    "users", "properties", "property_images", "leads", "visits", "favorites",
    "saved_searches", "collections", "collection_items", "tasks", "notifications",
    "activity_log", "settings", "property_views", "saved_search_alerts", "messages",
    "site_pages", "privacy_requests", "automation_rules",
]


class ExporterTest(unittest.TestCase):
    def test_exports_all_audited_tables_and_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "legacy.sqlite"
            output = root / "export"
            connection = sqlite3.connect(database)
            for table in TABLES:
                connection.execute(f'CREATE TABLE "{table}" (id INTEGER PRIMARY KEY, value TEXT)')
                connection.execute(f'INSERT INTO "{table}" (value) VALUES (?)', (table,))
            connection.execute("CREATE TABLE sessions (sid TEXT PRIMARY KEY, sess TEXT, expire INTEGER)")
            connection.execute(
                "CREATE TABLE password_reset_tokens (id INTEGER PRIMARY KEY, token_hash TEXT)"
            )
            connection.commit()
            connection.close()

            result = subprocess.run(
                [
                    sys.executable,
                    str(EXPORTER),
                    "--database",
                    str(database),
                    "--output",
                    str(output),
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            summary = json.loads(result.stdout)
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))

            self.assertEqual("exported", summary["status"])
            self.assertEqual(len(TABLES), summary["tables"])
            self.assertEqual(len(TABLES), summary["rows"])
            self.assertEqual(1, manifest["tables"]["users"]["rows"])
            self.assertIn("sessions", manifest["excluded_tables"])
            self.assertTrue((output / "users.jsonl").is_file())
            self.assertEqual("users", json.loads((output / "users.jsonl").read_text())["value"])


if __name__ == "__main__":
    unittest.main()
