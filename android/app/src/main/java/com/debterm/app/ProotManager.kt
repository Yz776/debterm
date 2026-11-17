package com.debterm.app

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class ProotManager(private val context: Context) {
    private val TAG = "DebTerm-ProotMgr"

    val filesDir: File = context.filesDir
    val rootfsDir: File = File(filesDir, "debian-rootfs")
    val binDir: File = File(filesDir, "bin")
    val tmpDir: File = File(filesDir, "tmp")
    val prootBin: File = File(binDir, "proot")
    val busyboxBin: File = File(binDir, "busybox")

    fun isInstalled(): Boolean {
        val bash = File(rootfsDir, "bin/bash")
        val osRelease = File(rootfsDir, "etc/os-release")
        val debianVer = File(rootfsDir, "etc/debian_version")
        return bash.exists() && (osRelease.exists() || debianVer.exists())
    }

    suspend fun installDebian(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Initializing directories...")
            if (!binDir.exists()) binDir.mkdirs()
            if (!tmpDir.exists()) tmpDir.mkdirs()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // 1. Extract bundled native tools (proot + busybox) for device ABI
            onProgress("Setting up core PRoot & BusyBox tools...")
            copyNativeTools()

            // 2. Download Debian rootfs
            val (pdArch, lxcArch) = getArchIdentifiers()
            onProgress("Downloading Debian 12 Bookworm rootfs ($pdArch)...")
            val rootfsArchive = File(filesDir, "rootfs.tar.xz")

            val mirrorUrls = listOf(
                "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-$pdArch-pd-v4.17.3.tar.xz",
                "https://images.linuxcontainers.org/images/debian/bookworm/$lxcArch/default/20261115_05%3A24/rootfs.tar.xz"
            )

            var downloadSuccess = false
            var lastError: Exception? = null

            for (url in mirrorUrls) {
                try {
                    Log.i(TAG, "Attempting rootfs download from: $url")
                    downloadFileWithRedirects(url, rootfsArchive) { pct ->
                        onProgress("Downloading Debian ($pdArch): $pct%")
                    }
                    if (rootfsArchive.exists() && rootfsArchive.length() > 5_000_000) {
                        downloadSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Download failed from $url: ${e.message}")
                    lastError = e
                    rootfsArchive.delete()
                }
            }

            if (!downloadSuccess) {
                throw IllegalStateException("Failed to download Debian rootfs from all mirrors: ${lastError?.message}")
            }

            // 3. Extract rootfs using bundled BusyBox
            onProgress("Extracting rootfs (using high-speed BusyBox engine)...")
            extractTarXz(rootfsArchive, rootfsDir)
            rootfsArchive.delete()

            // 4. Configure Debian networking, DNS & policy-rc.d shim
            onProgress("Configuring networking & environment...")

            val etcDir = File(rootfsDir, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()

            val resolvFile = File(etcDir, "resolv.conf")
            if (resolvFile.exists()) resolvFile.delete()
            resolvFile.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

            // Setup policy-rc.d shim (enables 100% of apt package daemon installations to succeed in PRoot)
            val sbinDir = File(rootfsDir, "usr/sbin")
            if (!sbinDir.exists()) sbinDir.mkdirs()
            val policyRcd = File(sbinDir, "policy-rc.d")
            policyRcd.writeText("#!/bin/sh\nexit 101\n")
            try {
                Os.chmod(policyRcd.absolutePath, 493) // 0755
            } catch (ignored: Throwable) {
                policyRcd.setExecutable(true, false)
            }

            // 5. Hostname
            File(etcDir, "hostname").writeText("debterm\n")

            // 6. Default bash profile & welcome banner
            val rootHome = File(rootfsDir, "root")
            if (!rootHome.exists()) rootHome.mkdirs()
            val bashrc = File(rootHome, ".bashrc")
            val welcomeBanner = """
                export TERM=xterm-256color
                PS1='\[\033[01;32m\]root@debterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                alias ll='ls -la --color=auto'
                alias update='apt update && apt upgrade -y'
                echo -e "\033[1;36m========================================================\033[0m"
                echo -e "\033[1;32m  Welcome to DebTerm (PRoot Debian 12 on Android)       \033[0m"
                echo -e "\033[1;33m  Full Glibc & Debian APT Package Repository enabled!   \033[0m"
                echo -e "\033[1;36m========================================================\033[0m"
            """.trimIndent()
            bashrc.appendText("\n$welcomeBanner\n")

            onProgress("Debian rootfs ready!")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            onProgress("Installation failed: ${e.message}")
            return@withContext false
        }
    }

    private fun getArchIdentifiers(): Pair<String, String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> Pair("aarch64", "arm64")
            abi.contains("x86_64") -> Pair("x86_64", "amd64")
            abi.contains("armeabi") -> Pair("arm", "armhf")
            else -> Pair("aarch64", "arm64")
        }
    }

    private fun copyNativeTools() {
        val abiCandidates = mutableListOf<String>()
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        abiCandidates.add(primaryAbi)
        for (a in Build.SUPPORTED_ABIS) {
            if (!abiCandidates.contains(a)) abiCandidates.add(a)
        }
        // Fallback default
        if (!abiCandidates.contains("arm64-v8a")) abiCandidates.add("arm64-v8a")

        fun copyAsset(assetSubpath: String, destFile: File): Boolean {
            for (abi in abiCandidates) {
                val fullPath = "$abi/$assetSubpath"
                try {
                    context.assets.open(fullPath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    try {
                        Os.chmod(destFile.absolutePath, 493) // 0755
                    } catch (t: Throwable) {
                        destFile.setExecutable(true, false)
                    }
                    Log.i(TAG, "Successfully extracted $fullPath to ${destFile.absolutePath}")
                    return true
                } catch (e: Exception) {
                    // Try next ABI candidate
                }
            }
            return false
        }

        if (!busyboxBin.exists() || busyboxBin.length() == 0L) {
            val ok = copyAsset("busybox", busyboxBin)
            if (!ok) Log.w(TAG, "BusyBox asset not found for ABIs: $abiCandidates")
        } else {
            busyboxBin.setExecutable(true, false)
        }

        if (!prootBin.exists() || prootBin.length() == 0L) {
            val ok = copyAsset("proot", prootBin)
            if (!ok) Log.w(TAG, "PRoot asset not found for ABIs: $abiCandidates")
        } else {
            prootBin.setExecutable(true, false)
        }
    }

    private fun downloadFileWithRedirects(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        var currentUrl = urlStr
        var conn: HttpURLConnection? = null
        val maxRedirects = 6

        for (redirect in 0 until maxRedirects) {
            val url = URL(currentUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 40000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DebTerm/2.0 (Android; Linux)")
            }

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == 307 || status == 308
            ) {
                val redirectLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (redirectLocation != null) {
                    currentUrl = if (redirectLocation.startsWith("http")) {
                        redirectLocation
                    } else {
                        URL(url, redirectLocation).toString()
                    }
                    continue
                }
            }
            break
        }

        val activeConn = conn ?: throw IOException("Could not establish connection to $urlStr")
        if (activeConn.responseCode !in 200..299) {
            val errorMsg = "HTTP error ${activeConn.responseCode} while downloading $currentUrl"
            activeConn.disconnect()
            throw IOException(errorMsg)
        }

        val totalLength = activeConn.contentLength.toLong()
        var downloaded: Long = 0

        activeConn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalLength > 0) {
                        val pct = ((downloaded * 100) / totalLength).toInt()
                        onProgress(pct)
                    }
                }
            }
        }
        activeConn.disconnect()
    }

    private fun extractTarXz(tarFile: File, destDir: File) {
        // Use bundled busybox binary if available
        val pb = if (busyboxBin.exists() && busyboxBin.canExecute()) {
            ProcessBuilder(busyboxBin.absolutePath, "tar", "-xJf", tarFile.absolutePath, "-C", destDir.absolutePath)
        } else {
            // Fallback to system tar
            ProcessBuilder("tar", "-xJf", tarFile.absolutePath, "-C", destDir.absolutePath)
        }

        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Log.e(TAG, "Rootfs extraction error (exit code $exitCode): $output")
            throw IOException("Extraction error ($exitCode): $output")
        }

        Log.i(TAG, "Rootfs extraction completed successfully!")
    }

    fun buildProotCommand(): Pair<String, Array<String>> {
        val cmd = if (prootBin.exists()) prootBin.absolutePath else "proot"
        val args = mutableListOf(
            "-0",
            "-r", rootfsDir.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/pts"
        )

        // Direct hardware device nodes for GPU & memory acceleration
        val hwNodes = listOf(
            "/dev/kgsl-3d0",    // Qualcomm Adreno GPU
            "/dev/mali0",       // ARM Mali GPU
            "/dev/mali",        // ARM Mali
            "/dev/dri",         // Direct Rendering Infrastructure / DRM
            "/dev/nvhost-gpu",  // Nvidia Tegra
            "/dev/pvr_sync",    // PowerVR
            "/dev/ashmem",      // Android Shared Memory
            "/dev/ion",         // Android ION Memory
            "/dev/dma_heap",    // DMA-BUF heaps
            "/dev/graphics"     // Framebuffer
        )
        for (node in hwNodes) {
            if (File(node).exists()) {
                args.add("-b")
                args.add(node)
            }
        }

        // Android System, Vendor Drivers, CPU & Sensors
        val sysDirs = listOf(
            "/system",
            "/vendor",
            "/apex",
            "/sys/devices/system/cpu",
            "/sys/class/thermal",
            "/sys/class/power_supply"
        )
        for (dir in sysDirs) {
            if (File(dir).exists()) {
                args.add("-b")
                args.add(dir)
            }
        }

        // Shared Android External Storage
        val sdcard = File("/sdcard")
        if (sdcard.exists()) {
            args.add("-b")
            args.add("/sdcard:/sdcard")
        }

        args.addAll(listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "/bin/bash", "--login"
        ))
        return Pair(cmd, args.toTypedArray())
    }

    fun getProotEnvironment(): Array<String> {
        return arrayOf(
            "PROOT_NO_SECCOMP=1",
            "TERM=xterm-256color",
            "TMPDIR=${tmpDir.absolutePath}",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
    }
}
