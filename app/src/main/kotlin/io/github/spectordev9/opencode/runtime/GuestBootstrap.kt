package io.github.spectordev9.opencode.runtime

import android.content.Context
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Prepares the writable parts of the pinned Alpine guest before PRoot starts.
 *
 * Android has no resolv.conf for musl, and Alpine minirootfs has no Git
 * identity, so a terminal or `git` command otherwise fails with DNS errors or
 * "Author identity unknown". DNS is refreshed on each launch; the Git identity
 * and shell profile are created once so user edits survive. Files under
 * /workspace are untouched.
 */
object GuestBootstrap {
    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_HOME = "/root"

    fun prepare(context: Context, rootfs: Path, hostWorkspace: Path) {
        Files.createDirectories(hostWorkspace)
        val rootHome = Files.createDirectories(rootfs.resolve("root"))
        val etc = Files.createDirectories(rootfs.resolve("etc"))
        val tmp = Files.createDirectories(rootfs.resolve("tmp"))

        writeIfChanged(
            etc.resolve("resolv.conf"),
            dnsServers(context).joinToString("\n", postfix = "\n") { "nameserver $it" } +
                "options timeout:1 attempts:2\n",
        )
        writeIfMissing(rootHome.resolve(".gitconfig"), GIT_CONFIG)
        writeIfMissing(
            etc.resolve("profile"),
            "export HOME=$GUEST_HOME\n" +
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n" +
                "export LANG=C.UTF-8\n",
        )
        // The pinned rootfs is installed read-only in spirit; these are the
        // only directories a guest session needs to write.
        listOf(tmp, rootHome.resolve(".cache"), rootHome.resolve(".config")).forEach {
            Files.createDirectories(it)
        }
    }

    fun environment(rootfs: Path, root: Path): MutableMap<String, String> = mutableMapOf(
        "HOME" to GUEST_HOME,
        "TMPDIR" to "/tmp",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8",
        "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
        "GIT_CONFIG_GLOBAL" to "$GUEST_HOME/.gitconfig",
        "GIT_CONFIG_SYSTEM" to "/dev/null",
        "GIT_SSL_CAINFO" to "/etc/ssl/certs/ca-certificates.crt",
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_EDITOR" to "true",
        "GIT_PAGER" to "cat",
        "PROOT_TMP_DIR" to root.resolve("tmp").toString(),
    )

    private fun dnsServers(context: Context): List<String> = SystemDnsServers.get(context)

    private fun writeIfMissing(path: Path, content: String) {
        if (Files.isRegularFile(path)) return
        writeIfChanged(path, content)
    }

    private fun writeIfChanged(path: Path, content: String) {
        if (Files.isRegularFile(path) &&
            String(Files.readAllBytes(path), Charsets.UTF_8) == content
        ) {
            return
        }
        Files.write(
            path,
            content.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private const val GIT_CONFIG = """[user]
	name = OpenCode Mobile
	email = opencode@localhost
[init]
	defaultBranch = main
[safe]
	directory = *
[credential]
	helper =
[core]
	pager = cat
"""
}
