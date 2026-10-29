import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
spec = importlib.util.spec_from_file_location("provenance", Path(__file__).with_name("check-apk-provenance.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ProvenanceTest(unittest.TestCase):
    def test_exact_clean_source_required(self):
        commit = "a" * 40
        for dirty, sha, good in [(False, commit, True), (True, commit, False), (False, "b" * 40, False), (None, commit, False)]:
            with tempfile.TemporaryDirectory() as tmp:
                apk = Path(tmp) / "app.apk"
                with zipfile.ZipFile(apk, "w") as z:
                    z.writestr("assets/build-provenance.json", json.dumps({"git_commit": sha, "dirty_build": dirty}))
                if good:
                    module.check(apk, commit)
                else:
                    with self.assertRaises(ValueError): module.check(apk, commit)

if __name__ == "__main__": unittest.main()
