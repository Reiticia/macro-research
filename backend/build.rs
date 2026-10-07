use std::{env, path::Path, process::Command};

fn git(manifest_dir: &Path, args: &[&str]) -> Option<String> {
    let output = Command::new("git")
        .current_dir(manifest_dir)
        .args(args)
        .output()
        .ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).trim().to_owned())
}

fn select_commit(
    explicit: Option<&str>,
    github: Option<&str>,
    repository: Option<&str>,
) -> Result<String, &'static str> {
    for (value, error) in [
        (
            explicit,
            "BACKEND_GIT_COMMIT must be a full Git commit hash",
        ),
        (github, "GITHUB_SHA must be a full Git commit hash"),
        (
            repository,
            "git rev-parse HEAD returned an invalid commit hash",
        ),
    ] {
        if let Some(value) = value.map(str::trim).filter(|value| !value.is_empty()) {
            if matches!(value.len(), 40 | 64) && value.bytes().all(|byte| byte.is_ascii_hexdigit())
            {
                return Ok(value.to_ascii_lowercase());
            }
            return Err(error);
        }
    }
    Ok("unknown".to_owned())
}

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=BACKEND_GIT_COMMIT");
    println!("cargo:rerun-if-env-changed=GITHUB_SHA");
    let manifest_dir = env::var_os("CARGO_MANIFEST_DIR").expect("Cargo manifest directory");
    let manifest_dir = Path::new(&manifest_dir);

    // Track detached HEAD, branch refs and packed refs so incremental/local builds cannot
    // reuse a stale revision. --git-path also handles Git worktrees correctly.
    let mut refs = vec!["HEAD".to_owned(), "packed-refs".to_owned()];
    if let Some(branch) = git(manifest_dir, &["symbolic-ref", "-q", "HEAD"]) {
        refs.push(branch);
    }
    for reference in refs {
        if let Some(path) = git(manifest_dir, &["rev-parse", "--git-path", &reference]) {
            let path = manifest_dir.join(path);
            println!("cargo:rerun-if-changed={}", path.display());
        }
    }

    let explicit = env::var("BACKEND_GIT_COMMIT").ok();
    let github = env::var("GITHUB_SHA").ok();
    let repository = git(manifest_dir, &["rev-parse", "HEAD"]);
    let commit = select_commit(
        explicit.as_deref(),
        github.as_deref(),
        repository.as_deref(),
    )
    .unwrap_or_else(|error| panic!("{error}"));
    if commit == "unknown" {
        println!("cargo:warning=Git metadata unavailable; startup notice will report unknown");
    }
    println!("cargo:rustc-env=BACKEND_GIT_COMMIT={commit}");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn explicit_commit_takes_priority_over_ci_and_repository() {
        let explicit = "a".repeat(40);
        let github = "b".repeat(40);
        let repository = "c".repeat(40);
        assert_eq!(
            select_commit(Some(&explicit), Some(&github), Some(&repository)).unwrap(),
            explicit
        );
        assert_eq!(
            select_commit(None, Some(&github), Some(&repository)).unwrap(),
            github
        );
        assert_eq!(
            select_commit(None, None, Some(&repository)).unwrap(),
            repository
        );
    }

    #[test]
    fn empty_sources_fall_back_and_missing_metadata_is_honest() {
        let repository = "a".repeat(40);
        assert_eq!(
            select_commit(Some(" "), Some(""), Some(&repository)).unwrap(),
            repository
        );
        assert_eq!(select_commit(None, None, None).unwrap(), "unknown");
    }

    #[test]
    fn full_sha1_and_sha256_hashes_are_normalized() {
        for length in [40, 64] {
            let hash = "A".repeat(length);
            assert_eq!(
                select_commit(Some(&format!(" {hash}\n")), None, None).unwrap(),
                hash.to_ascii_lowercase()
            );
        }
    }

    #[test]
    fn invalid_or_short_hashes_do_not_silently_report_the_wrong_build() {
        let repository = "a".repeat(40);
        for invalid in [
            "8452a7f",
            "unknown",
            "main",
            "abc\ncargo:rustc-env=other=value",
        ] {
            assert!(select_commit(Some(invalid), None, Some(&repository)).is_err());
        }
        assert!(select_commit(None, Some(&"z".repeat(40)), Some(&repository)).is_err());
    }
}
