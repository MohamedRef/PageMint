package com.mohamedref.pagemint.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** GitHub Releases-based update transport.
 *
 * All download bytes are verified against GitHub's SHA-256 asset digest.
 * The package ID, versionCode and signing certificate are checked before
 * Android's package installer gets the APK. Android does the final signing
 * verification, and installation always requires the user's approval.
 */
object ReleaseUpdater {
    private const val REPO_API = "https://api.github.com/repos/MohamedRef/PageMint/releases/latest"
    private const val ASSET_NAME = "PageMint-release.apk"
    private const val MAX_APK_BYTES = 250_000_000L
    private const val MAX_JSON_BYTES = 1_048_576
    private val shaPattern = Regex("""^sha256:([a-fA-F0-9]{64})$""")

    data class Release(
        val version: ReleaseVersion.Version,
        val downloadUrl: String,
        val sha256: String,
        val bytes: Long,
        val notes: String,
    )

    sealed interface Check {
        data class Available(val release: Release) : Check
        data object Current : Check
        data object NotPublished : Check
    }

    class SigningMismatch : IOException(
        "The installed APK uses a different signing certificate. " +
            "Install the permanently signed PageMint release once; future signed updates " +
            "can then install over it without uninstalling."
    )

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        val connection = try {
            openHttps(URL(REPO_API), apiRequest = true)
        } catch (exception: HttpError) {
            if (exception.code == 404) return@withContext Check.NotPublished
            throw exception
        }
        val response = try {
            val payload = readLimited(connection, MAX_JSON_BYTES)
            JSONObject(payload.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
        check(!response.optBoolean("draft", false)) { "Release is not published" }
        check(!response.optBoolean("prerelease", false)) { "Only stable releases are supported" }

        val version = ReleaseVersion.parse(response.optString("tag_name"))
            ?: throw IOException("Release tag must be vMAJOR.MINOR.PATCH")
        val asset = response.optJSONArray("assets")
            ?.let { assets ->
                (0 until assets.length())
                    .asSequence()
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == ASSET_NAME }
            } ?: throw IOException("The release is missing $ASSET_NAME")
        val url = asset.optString("browser_download_url")
        val parsed = URL(url)
        if (parsed.protocol != "https" || parsed.host != "github.com" ||
            !parsed.path.startsWith("/MohamedRef/PageMint/releases/download/") ||
            !parsed.path.endsWith("/$ASSET_NAME")
        ) {
            throw IOException("The release has an unexpected APK download URL")
        }
        val sha256 = shaPattern.matchEntire(asset.optString("digest"))
            ?.groupValues?.get(1)?.lowercase()
            ?: throw IOException("The release asset is missing its SHA-256 digest")
        val size = asset.optLong("size", -1L)
        if (size !in 1L..MAX_APK_BYTES) {
            throw IOException("Unexpected release APK size")
        }
        if (version.code > installedVersionCode(context)) {
            Check.Available(
                Release(
                    version = version,
                    downloadUrl = url,
                    sha256 = sha256,
                    bytes = size,
                    notes = response.optString("body").take(1200),
                )
            )
        } else {
            Check.Current
        }
    }

    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, ASSET_NAME)
        if (file.isFile && file.length() == release.bytes && digestFile(file) == release.sha256) {
            validateApk(context, file, release)
            onProgress(100)
            return@withContext file
        }
        val tmp = File(directory, "$ASSET_NAME.part")
        tmp.delete()
        try {
            val connection = openHttps(URL(release.downloadUrl), apiRequest = false)
            try {
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_APK_BYTES ||
                    (declaredLength > 0 && declaredLength != release.bytes)
                ) throw IOException("Download size does not match GitHub release")
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var lastProgress = -1
                connection.inputStream.use { input ->
                    tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            written += n
                            if (written > MAX_APK_BYTES || written > release.bytes) {
                                throw IOException("Release APK exceeds expected size")
                            }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val percent = ((written * 100) / release.bytes).toInt().coerceIn(0, 100)
                            if (percent >= lastProgress + 5 || percent == 100) {
                                lastProgress = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                if (written != release.bytes ||
                    hex(digest.digest()) != release.sha256
                ) throw IOException("Release APK checksum verification failed")
            } finally {
                connection.disconnect()
            }
            validateApk(context, tmp, release)
            Files.move(
                tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
            )
            file
        } catch (error: Exception) {
            tmp.delete()
            throw error
        }
    }

    private fun installedVersionCode(context: Context): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return packageVersionCode(packageInfo)
    }

    private fun packageVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** Reject wrong package, version or signing key before invoking the system installer. */
    private fun validateApk(context: Context, file: File, release: Release) {
        val manager = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES.toLong()
        }
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flag))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.absolutePath, flag.toInt())
        } ?: throw IOException("Downloaded file is not a valid Android APK")
        if (archive.packageName != context.packageName) {
            throw IOException("APK package name does not match PageMint")
        }
        val version = packageVersionCode(archive)
        if (version != release.version.code || version <= installedVersionCode(context)) {
            throw IOException("APK version does not match this release or is not newer")
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val installed = if (Build.VERSION.SDK_INT >= 33) {
                manager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(flag),
                )
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, flag.toInt())
            }
            val existingSigners = installed.signingInfo?.apkContentsSigners
            val updateSigners = archive.signingInfo?.apkContentsSigners
            if (existingSigners.isNullOrEmpty() || updateSigners.isNullOrEmpty() ||
                existingSigners.size != updateSigners.size ||
                existingSigners.indices.any { index ->
                    !MessageDigest.isEqual(
                        existingSigners[index].toByteArray(),
                        updateSigners[index].toByteArray(),
                    )
                }
            ) {
                throw SigningMismatch()
            }
        }
    }

    private class HttpError(val code: Int) : IOException("GitHub returned HTTP $code")

    private fun openHttps(url: URL, apiRequest: Boolean): HttpsURLConnection {
        var current = url
        repeat(6) {
            val trusted = current.protocol.equals("https", ignoreCase = true) &&
                (if (apiRequest) current.host == "api.github.com"
                 else current.host in setOf(
                     "github.com", "release-assets.githubusercontent.com",
                     "objects.githubusercontent.com",
                 ))
            if (!trusted) throw IOException("Untrusted GitHub download redirect")
            val connection = current.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "PageMint-Android-Updater")
            connection.setRequestProperty(
                "Accept",
                if (apiRequest) "application/vnd.github+json" else "application/octet-stream",
            )
            val status = connection.responseCode
            when (status) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("Missing redirect target")
                    current = URL(current, location)
                }
                else -> {
                    connection.disconnect()
                    throw HttpError(status)
                }
            }
        }
        throw IOException("Too many download redirects")
    }

    private fun readLimited(connection: HttpsURLConnection, maxBytes: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > maxBytes) throw IOException("Release metadata too large")
                bytes.write(buffer, 0, n)
            }
        }
        return bytes.toByteArray()
    }

    private fun digestFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { b -> "%02x".format(b.toInt() and 0xff) }
}
