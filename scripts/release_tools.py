#!/usr/bin/env python3
"""Unified release contracts. No credentials/configuration files are read or printed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
SHA = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
MAX_METADATA = 1024 * 1024
MAX_BINARY = 512 * 1024 * 1024
REPO = os.environ.get("GH_REPO", "Reiticia/macro-research")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field")
        result[key] = value
    return result


def load_bytes(data):
    require(len(data) <= MAX_METADATA, "Metadata is too large")
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)


def load(path):
    return load_bytes(Path(path).read_bytes())


def write(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def validate_notes(notes, version, code=None):
    require(notes.get("schemaVersion") == 1 and type(notes["schemaVersion"]) is int, "Invalid notes schema")
    require(notes.get("versionName") == version and VERSION.fullmatch(version), "Notes version mismatch")
    if code is not None:
        require(notes.get("versionCode") == code and type(code) is int and code > 0, "Notes build mismatch")
    languages = notes.get("notes", {})
    require(set(languages) == {"en", "zh-CN", "zh-TW"}, "All three notes languages are required")
    for groups in languages.values():
        require(set(groups) == {"client", "backend"}, "Invalid notes groups")
        for group, items in groups.items():
            require(type(items) is list and len(items) <= 30 and (group != "client" or items), "Invalid notes array")
            require(all(isinstance(item, str) and item.strip() and len(item.encode("utf-16-le")) // 2 <= 1000 for item in items), "Invalid notes item")
    return notes


def source_config(root):
    text = (root / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'^\s*versionName = "([^"]+)"', text, re.M).group(1)
    code = int(re.search(r'^\s*versionCode = (\d+)', text, re.M).group(1))
    require(VERSION.fullmatch(version) and code > 0, "Invalid application version")
    validate_notes(load(root / f"android/release-notes/{version}.json"), version)
    return version, code


def names(version):
    return {
        f"macro-research-{version}.apk",
        f"market-event-analyzer-{version}-x86_64-unknown-linux-gnu.tar.gz",
        f"market-event-analyzer-{version}-x86_64-unknown-linux-gnu.tar.gz.sha256",
        "release-notes.json", "update.json", "release.json", "SHA256SUMS.txt",
    }


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def apk_notes(apk):
    with zipfile.ZipFile(apk) as archive:
        entry = archive.getinfo("assets/release-notes.json")
        require(entry.file_size <= MAX_METADATA, "APK notes too large")
        return archive.read(entry)


def prepare_apk(apk, out, metadata):
    version = metadata["versionName"]
    require(metadata["packageName"] == "com.macroresearch" and VERSION.fullmatch(version), "APK identity mismatch")
    require(type(metadata["versionCode"]) is int and metadata["versionCode"] > 0 and metadata["minSdk"] >= 26, "Invalid APK metadata")
    data = apk_notes(apk)
    notes = validate_notes(load_bytes(data), version, metadata["versionCode"])
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"macro-research-{version}.apk"
    if Path(apk).resolve() != target.resolve():
        shutil.copyfile(apk, target)
    require(0 < target.stat().st_size <= MAX_BINARY, "APK size exceeds limit")
    (out / "release-notes.json").write_bytes(data)
    manifest = dict(schemaVersion=1, **metadata, apkAssetName=target.name,
                    apkSize=target.stat().st_size, apkSha256=digest(target), releaseNotes=notes)
    write(out / "update.json", manifest)
    return manifest


def actual_apk_metadata(aapt, apk):
    result = subprocess.run([aapt, "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", result, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", result, re.M)
    require(package is not None and sdk is not None, "Could not read APK metadata")
    return dict(packageName=package[1], versionCode=int(package[2]), versionName=package[3], minSdk=int(sdk[1]))


def validate_backend(out, version, commit):
    filename = f"market-event-analyzer-{version}-x86_64-unknown-linux-gnu.tar.gz"
    archive = out / filename
    require(0 < archive.stat().st_size <= MAX_BINARY, "Invalid backend archive size")
    expected = f"{digest(archive)}  {filename}\n"
    require((out / (filename + ".sha256")).read_text(encoding="utf-8") == expected, "Backend checksum mismatch")
    with tarfile.open(archive, "r:gz") as package:
        entry = package.getmember(filename.removesuffix(".tar.gz") + "/README.txt")
        require(entry.size <= MAX_METADATA, "Invalid backend README")
        text = package.extractfile(entry).read().decode("utf-8")
        require(f"构建提交: {commit}\n" in text, "Backend was not built from the release commit")


def validate_data(out, version):
    update = load(out / "update.json")
    require(update.get("schemaVersion") == 1 and update.get("versionName") == version, "Invalid update version/schema")
    require(update.get("packageName") == "com.macroresearch", "Invalid package")
    code = update.get("versionCode")
    require(type(code) is int and code > 0 and type(update.get("minSdk")) is int and update["minSdk"] >= 26, "Invalid update build")
    expected = f"macro-research-{version}.apk"
    require(update.get("apkAssetName") == expected and 0 < update.get("apkSize", 0) <= MAX_BINARY, "Invalid APK asset")
    require((out / expected).stat().st_size == update["apkSize"] and digest(out / expected) == update["apkSha256"], "APK integrity mismatch")
    notes = load(out / "release-notes.json")
    validate_notes(notes, version, code)
    require(notes == update["releaseNotes"] and apk_notes(out / expected) == (out / "release-notes.json").read_bytes(), "Release notes differ from APK")


def assemble(out, version, commit):
    require(COMMIT.fullmatch(commit), "Invalid source commit")
    validate_data(out, version)
    validate_backend(out, version, commit)
    files = sorted(names(version) - {"release.json", "SHA256SUMS.txt"})
    write(out / "release.json", dict(schemaVersion=1, versionName=version, gitCommit=commit,
        assets=[dict(name=name, size=(out / name).stat().st_size, sha256=digest(out / name)) for name in files]))
    (out / "SHA256SUMS.txt").write_text("".join(f"{digest(out / name)}  {name}\n" for name in sorted(names(version) - {"SHA256SUMS.txt"})), encoding="utf-8")
    verify(out, version, commit)


def verify(out, version, commit):
    require(all((out / name).is_file() for name in names(version)), "Incomplete unified release")
    provenance = load(out / "release.json")
    require(provenance.get("schemaVersion") == 1 and provenance.get("versionName") == version and provenance.get("gitCommit") == commit and COMMIT.fullmatch(commit), "Release source mismatch")
    expected = names(version) - {"release.json", "SHA256SUMS.txt"}
    rows = provenance.get("assets", [])
    require(len(rows) == len(expected) and {row.get("name") for row in rows} == expected, "Invalid provenance assets")
    for row in rows:
        path = out / row["name"]
        require(row["size"] == path.stat().st_size and 0 < row["size"] <= MAX_BINARY and row["sha256"] == digest(path), "Release asset integrity mismatch")
    expected_sums = "".join(f"{digest(out / name)}  {name}\n" for name in sorted(names(version) - {"SHA256SUMS.txt"}))
    require((out / "SHA256SUMS.txt").read_text(encoding="utf-8") == expected_sums, "Release checksums differ")
    validate_data(out, version)
    validate_backend(out, version, commit)


def body(notes):
    lines = [f"# Macro Research {notes['versionName']}", ""]
    for language, groups in notes["notes"].items():
        lines += [f"## {language}", ""]
        for name, items in groups.items():
            if items:
                lines += [f"### {name}"] + [f"- {item}" for item in items] + [""]
    lines += ["## Install / 安装 / 安裝", "",
              "Android: download the APK; use the same signing identity to upgrade without losing data.",
              "Android：下载 APK，使用相同签名覆盖升级；无需卸载或清除数据。",
              "Android：下載 APK，使用相同簽章覆蓋升級；無須解除安裝或清除資料。", "",
              "Backend: Linux x86_64 glibc (Ubuntu 22.04+), built from the same source commit.",
              "后端：解压 Linux x86_64 glibc 压缩包，按 docs/backend.md 部署；config.toml 仅为安全模板。",
              "後端：解壓縮 Linux x86_64 glibc 封存檔，依 docs/backend.md 部署；config.toml 僅為安全範本。", "",
              "Verify downloads with SHA256SUMS.txt. Package versions identify this unified release, not the Rust crate version.", ""]
    return "\n".join(lines)


def gh(*arguments, missing_ok=False):
    result = subprocess.run(["gh", *arguments], capture_output=True)
    if result.returncode:
        if missing_ok and b"HTTP 404" in result.stderr:
            return None
        raise RuntimeError("GitHub operation failed; no release was overwritten")
    return result.stdout


def remote_release(tag):
    result = gh("api", f"repos/{REPO}/releases/tags/{tag}", missing_ok=True)
    if result is not None:
        return load_bytes(result)
    # Some tag lookup paths do not expose drafts. Authenticated listing must also be inspected
    # before creating a new draft, so failed-job reruns can resume the existing publication.
    for page in range(1, 4):
        rows = load_bytes(gh("api", f"repos/{REPO}/releases?per_page=100&page={page}"))
        for release in rows:
            if release.get("tag_name") == tag:
                return release
        if len(rows) < 100:
            break
    return None


def check_remote(release, version, commit):
    require(release.get("tag_name") == "v" + version and not release.get("prerelease"), "Unexpected release tag/channel")
    assets = {row["name"]: row for row in release["assets"]}
    require(len(assets) == len(release["assets"]) and set(assets) == names(version), "Published release is incomplete or has unexpected assets; use a new tag")
    with tempfile.TemporaryDirectory() as directory:
        out = Path(directory)
        for name in sorted(names(version)):
            asset = assets[name]
            limit = MAX_BINARY if name.endswith((".apk", ".tar.gz")) else MAX_METADATA
            require(0 < asset["size"] <= limit, "Invalid remote asset size")
            # gh follows GitHub's official asset redirects; credentials never enter metadata/APK.
            content = gh("api", f"repos/{REPO}/releases/assets/{asset['id']}", "-H", "Accept: application/octet-stream")
            require(len(content) == asset["size"], "Remote asset size mismatch")
            (out / name).write_bytes(content)
        verify(out, version, commit)
        require((release.get("body") or "").strip() == body(load(out / "release-notes.json")).strip(), "Published release notes differ; use a new tag")


def publish(out, version, commit):
    verify(out, version, commit)
    tag = "v" + version
    existing = remote_release(tag)
    if existing is not None and not existing["draft"]:
        check_remote(existing, version, commit)
        return
    with tempfile.TemporaryDirectory() as directory:
        notes_file = Path(directory) / "body.md"
        notes_file.write_text(body(load(out / "release-notes.json")), encoding="utf-8")
        if existing is None:
            gh("release", "create", tag, "--repo", REPO, "--verify-tag", "--draft", "--title", tag, "--notes-file", str(notes_file), "--target", commit)
        else:
            require(existing.get("target_commitish") == commit, "Draft source differs; do not overwrite")
            for asset in existing["assets"]:
                gh("api", "--method", "DELETE", f"repos/{REPO}/releases/assets/{asset['id']}")
            gh("release", "edit", tag, "--repo", REPO, "--notes-file", str(notes_file), "--title", tag)
        gh("release", "upload", tag, *[str(out / name) for name in sorted(names(version))], "--repo", REPO)
        draft = remote_release(tag)
        require(draft is not None and draft["draft"] and draft.get("target_commitish") == commit, "Unexpected release state")
        check_remote(draft, version, commit)
        gh("release", "edit", tag, "--repo", REPO, "--draft=false", "--prerelease=false", "--latest")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["preflight", "apk", "assemble", "verify", "publish"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--out", type=Path, default=Path("dist"))
    parser.add_argument("--tag", default="")
    parser.add_argument("--commit", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt")
    args = parser.parse_args()
    version, code = source_config(args.root)
    if args.tag:
        require(args.tag == "v" + version, "Tag must match v<versionName>")
    if args.command == "preflight":
        skip = False
        if args.tag:
            existing = remote_release(args.tag)
            if existing is not None:
                if not existing["draft"]:
                    check_remote(existing, version, args.commit)
                    skip = True
                else:
                    require(existing.get("target_commitish") == args.commit, "Draft belongs to another commit")
        output = f"version={version}\nversion_code={code}\nskip={'true' if skip else 'false'}\n"
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                stream.write(output)
        print(output, end="")
    elif args.command == "apk":
        meta = actual_apk_metadata(args.aapt, args.apk)
        require(meta["versionName"] == version and meta["versionCode"] == code, "Compiled APK differs from build configuration")
        prepare_apk(args.apk, args.out, meta)
    elif args.command == "assemble":
        assemble(args.out, version, args.commit)
    elif args.command == "verify":
        verify(args.out, version, args.commit)
    elif args.command == "publish":
        require(args.tag, "Publishing requires a validated tag")
        publish(args.out, version, args.commit)


if __name__ == "__main__":
    main()
