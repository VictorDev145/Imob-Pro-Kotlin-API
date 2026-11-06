import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("import_to_postgres.py")
SPEC = importlib.util.spec_from_file_location("imobpro_importer", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ImportManifestTest(unittest.TestCase):
    def make_export(self, root: Path):
        tables = {}
        for name in sorted(MODULE.TABLES):
            filename = name + ".jsonl"
            data = ('{"id":1,"name":"' + name + '"}\n').encode()
            (root / filename).write_bytes(data)
            tables[name] = {
                "file": filename,
                "rows": 1,
                "primary_key": ["id"],
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        manifest = {"format_version": 1, "source_database_sha256": "a" * 64, "tables": tables}
        (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def test_manifest_validates_hashes_and_row_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.make_export(root)
            manifest, manifest_hash = MODULE.load_manifest(root)
            self.assertEqual(len(MODULE.TABLES), len(manifest["tables"]))
            self.assertEqual(64, len(manifest_hash))

    def test_manifest_rejects_tampered_table(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.make_export(root)
            with (root / "users.jsonl").open("a", encoding="utf-8") as stream:
                stream.write('{"id":2}\n')
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                MODULE.load_manifest(root)


if __name__ == "__main__":
    unittest.main()
