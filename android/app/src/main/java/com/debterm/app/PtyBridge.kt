package com.debterm.app

import java.io.FileDescriptor

object PtyBridge {
    init {
        System.loadLibrary("debterm-pty")
    }

    external fun createSubprocess(
        cmd: String,
        args: Array<String>,
        envVars: Array<String>,
        cwd: String?,
        rows: Int,
        cols: Int
    ): IntArray?

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    external fun waitFor(pid: Int): Int

    external fun closeFd(fd: Int)

    fun createJavaFileDescriptor(fd: Int): FileDescriptor {
        val descriptor = FileDescriptor()
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(descriptor, fd)
        } catch (e: Exception) {
            val field = FileDescriptor::class.java.getDeclaredField("fd")
            field.isAccessible = true
            field.setInt(descriptor, fd)
        }
        return descriptor
    }
}
