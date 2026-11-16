package com.debterm.app

import android.content.Context
import android.os.Build
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
    val prootBin: File = File(binDir, "proot")

    fun isInstalled(): Boolean {
        val bash = File(rootfsDir, "bin/bash")
        val debianVer = File(rootfsDir, "etc/debian_version")
        return bash.exists() && bash.canExecute() && debianVer.exists()
    }

    suspend fun installDebian(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Initializing directories...")
            if (!binDir.exists()) binDir.mkdirs()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // 1. Copy or extract bundled proot binary
            copyProotBinary()

            // 2. Download and unpack minimal Debian rootfs
            val arch = detectArch()
            onProgress("Downloading Debian rootfs ($arch)...")
            val rootfsArchive = File(filesDir, "rootfs.tar.xz")

            // Official mirror url
            val url = "https://images.linuxcontainers.org/images/debian/bookworm/$arch/default/20261115_05%3A24/rootfs.tar.xz"
            downloadFile(url, rootfsArchive) { pct ->
                onProgress("Downloading Debian Bookworm: $pct%")
            }

            onProgress("Extracting rootfs (this may take a minute)...")
            extractTarXz(rootfsArchive, rootfsDir)
            rootfsArchive.delete()

            // 3. Fix DNS and resolv.conf
            onProgress("Configuring networking & environment...")
            val resolvFile = File(rootfsDir, "etc/resolv.conf")
            if (resolvFile.exists()) resolvFile.delete()
            resolvFile.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

            // 4. Hostname
            File(rootfsDir, "etc/hostname").writeText("debterm\n")

            // 5. Default bash profile
            val bashrc = File(rootfsDir, "root/.bashrc")
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

    private fun detectArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("x86_64") -> "amd64"
            abi.contains("armeabi") -> "armhf"
            else -> "arm64"
        }
    }

    private fun copyProotBinary() {
        if (!prootBin.exists()) {
            try {
                context.assets.open("proot").use { input ->
                    FileOutputStream(prootBin).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "proot asset not found, assuming external or testing build: ${e.message}")
            }
        }
        prootBin.setExecutable(true, false)
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.connect()

        val totalLength = conn.contentLength
        var downloaded: Long = 0

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
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
    }

    private fun extractTarXz(tarFile: File, destDir: File) {
        val process = ProcessBuilder("tar", "-xJf", tarFile.absolutePath, "-C", destDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val err = process.inputStream.bufferedReader().readText()
            Log.e(TAG, "Extraction tar error: $err")
        }
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
            "TMPDIR=${filesDir.absolutePath}/tmp",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
    }
}
