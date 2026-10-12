package com.macroresearch.update

data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String?, val minSdk: Int?, val certificates: Set<String>)

/** Pure policy, shared by the platform adapter and tests. Rotation is intentionally unsupported. */
fun validateApkIdentity(archive: ApkIdentity, installed: ApkIdentity, expected: UpdateManifest, sdk: Int) {
    if (archive.packageName != expected.packageName || archive.packageName != installed.packageName ||
        archive.versionCode != expected.versionCode || archive.versionCode <= installed.versionCode ||
        archive.versionName != expected.versionName || archive.minSdk != expected.minSdk || expected.minSdk > sdk
    ) throw UpdateFailure(UpdateError.VERIFICATION)
    if (installed.certificates.isEmpty() || archive.certificates != installed.certificates) throw UpdateFailure(UpdateError.SIGNATURE)
}
