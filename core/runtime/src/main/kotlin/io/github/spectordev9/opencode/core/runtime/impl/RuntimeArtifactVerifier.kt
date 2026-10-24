package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.inputStream

class VerifiedRuntimeArtifact internal constructor(
    val path: Path,
    val sha256: String,
    val sizeBytes: Long,
)

class RuntimeArtifactVerifier {
    fun verify(archive: Path, expectedSha256: String): ApiResult<VerifiedRuntimeArtifact> {
        if (!expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            return ApiResult.Failure(ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "invalid artifact sha-256 pin"))
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            archive.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    digest.update(buffer, 0, read)
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                ApiResult.Failure(
                    ApiError(ErrorCode.E_UPSTREAM, "runtime artifact sha-256 mismatch"),
                )
            } else {
                ApiResult.Success(
                    VerifiedRuntimeArtifact(
                        path = archive,
                        sha256 = actualSha256,
                        sizeBytes = size,
                    ),
                )
            }
        } catch (failure: IOException) {
            ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, failure.message ?: "runtime artifact could not be read"),
            )
        }
    }
}
