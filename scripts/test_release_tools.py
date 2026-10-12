import copy
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import subprocess
import sys
from unittest.mock import patch
import zipfile

import release_tools as release

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "a" * 40


class ReleaseTests(unittest.TestCase):
    def fixture(self, directory):
        out = Path(directory)
        version, code = release.source_config(ROOT)
        notes = release.load(ROOT / f"android/release-notes/{version}.json")
        notes["versionCode"] = code
        apk = out / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/release-notes.json", json.dumps(notes, ensure_ascii=False).encode())
        release.prepare_apk(apk, out, dict(packageName="com.macroresearch", versionName=version, versionCode=code, minSdk=26))
        filename = f"market-event-analyzer-{version}-x86_64-unknown-linux-gnu.tar.gz"
        with tarfile.open(out / filename, "w:gz") as archive:
            content = f"构建提交: {COMMIT}\n".encode()
            entry = tarfile.TarInfo(filename.removesuffix(".tar.gz") + "/README.txt")
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
        (out / (filename + ".sha256")).write_text(f"{release.digest(out / filename)}  {filename}\n", encoding="utf-8")
        return out, version

    def test_complete_release_is_same_source_and_notes_are_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            out, version = self.fixture(directory)
            release.assemble(out, version, COMMIT)
            release.verify(out, version, COMMIT)
            self.assertEqual(set(release.load(out / "release.json")["assets"][0]), {"name", "size", "sha256"})
            self.assertIn("## zh-CN", release.body(release.load(out / "release-notes.json")))
            self.assertIn("## zh-TW", release.body(release.load(out / "release-notes.json")))
            self.assertEqual((out / "release-notes.json").read_bytes(), release.apk_notes(out / f"macro-research-{version}.apk"))
            with self.assertRaises(ValueError):
                release.verify(out, version, "b" * 40)
            (out / f"macro-research-{version}.apk").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                release.verify(out, version, COMMIT)

    def test_missing_and_mismatched_backend_cannot_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            out, version = self.fixture(directory)
            with self.assertRaises(ValueError):
                release.assemble(out, version, "b" * 40)
            (out / f"market-event-analyzer-{version}-x86_64-unknown-linux-gnu.tar.gz").unlink()
            with self.assertRaises(FileNotFoundError):
                release.assemble(out, version, COMMIT)

    def test_three_languages_and_bounds_are_mandatory(self):
        version, _ = release.source_config(ROOT)
        original = release.load(ROOT / f"android/release-notes/{version}.json")
        for mutate in [
            lambda notes: notes["notes"].pop("zh-TW"),
            lambda notes: notes.update(versionName="99.0.0"),
            lambda notes: notes["notes"]["en"].update(client=[]),
            lambda notes: notes["notes"]["en"].update(client=["a" * 1001]),
            lambda notes: notes.update(schemaVersion=True),
        ]:
            notes = copy.deepcopy(original)
            mutate(notes)
            with self.assertRaises(ValueError):
                release.validate_notes(notes, version)
        with self.assertRaises(ValueError):
            release.load_bytes(b'{"x":1,"x":2}')

    def test_existing_publication_is_verified_never_clobbered(self):
        with tempfile.TemporaryDirectory() as directory:
            out, version = self.fixture(directory)
            release.assemble(out, version, COMMIT)
            existing = {"draft": False}
            with patch.object(release, "remote_release", return_value=existing), patch.object(release, "check_remote") as verify, patch.object(release, "gh") as gh:
                release.publish(out, version, COMMIT)
                verify.assert_called_once_with(existing, version, COMMIT)
                gh.assert_not_called()
            with patch.object(release, "remote_release", return_value=existing), patch.object(release, "check_remote", side_effect=ValueError("Invalid publication")), patch.object(release, "gh") as gh:
                with self.assertRaises(ValueError):
                    release.publish(out, version, COMMIT)
                gh.assert_not_called()

    def test_only_verified_complete_draft_is_published(self):
        with tempfile.TemporaryDirectory() as directory:
            out, version = self.fixture(directory)
            release.assemble(out, version, COMMIT)
            draft = {"draft": True, "target_commitish": COMMIT}
            with patch.object(release, "remote_release", side_effect=[None, draft]), patch.object(release, "check_remote") as verify, patch.object(release, "gh") as gh:
                release.publish(out, version, COMMIT)
                self.assertIn("--draft", gh.call_args_list[0].args)
                verify.assert_called_once()
                self.assertIn("--draft=false", gh.call_args_list[-1].args)
                self.assertFalse(any("--clobber" in call.args for call in gh.call_args_list))
            with patch.object(release, "remote_release", side_effect=[None, draft]), patch.object(release, "check_remote", side_effect=ValueError("Missing asset")), patch.object(release, "gh") as gh:
                with self.assertRaises(ValueError):
                    release.publish(out, version, COMMIT)
                self.assertFalse(any("--draft=false" in call.args for call in gh.call_args_list))

    def test_draft_lookup_falls_back_to_authenticated_listing(self):
        draft = {"tag_name": "v1.0.0", "draft": True, "target_commitish": COMMIT}
        with patch.object(release, "gh", side_effect=[None, json.dumps([draft]).encode()]):
            self.assertEqual(draft, release.remote_release("v1.0.0"))
        with patch.object(release, "gh", side_effect=[None, b"[]"]):
            self.assertIsNone(release.remote_release("v1.0.0"))

    def test_bad_tag_fails_before_any_github_operation(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/release_tools.py"), "preflight", "--tag", "v999.0.0"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Tag must match", result.stderr)

    def test_missing_or_bad_versioned_notes_fail_source_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "android/app/build.gradle.kts"
            config.parent.mkdir(parents=True)
            config.write_text('versionName = "1.0.0"\nversionCode = 17\n', encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                release.source_config(root)
            notes = root / "android/release-notes/1.0.0.json"
            notes.parent.mkdir()
            notes.write_text("invalid JSON", encoding="utf-8")
            with self.assertRaises(ValueError):
                release.source_config(root)

    def test_workflows_keep_main_deploy_and_only_one_publisher(self):
        android = (ROOT / ".github/workflows/android-release.yml").read_text(encoding="utf-8")
        backend = (ROOT / ".github/workflows/backend-release.yml").read_text(encoding="utf-8")
        self.assertIn("tags: ['v*']", android)
        self.assertIn("workflow_dispatch:", android)
        self.assertIn("needs: [preflight, build, backend]", android)
        self.assertIn("if: github.ref_type == 'tag'", android)
        self.assertEqual(android.count("contents: write"), 1)
        self.assertNotIn("contents: write", backend)
        self.assertNotIn("  release:", backend)
        self.assertIn("branches: ['**']", backend)
        self.assertIn("github.ref == 'refs/heads/main'", backend)
        self.assertIn("secrets: inherit", backend)
        self.assertGreaterEqual(android.count("ref: ${{ github.sha }}"), 4)
        self.assertIn('--version "$RELEASE_VERSION"', android)
        self.assertEqual(android.count("overwrite: true"), 3)  # Only internal CI artifacts, never formal Release assets.
        self.assertNotIn("pattern: unified-*", android)


if __name__ == "__main__":
    unittest.main()
