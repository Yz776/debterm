/*
 * ==============================================================================
 * DebTerm Android JNI PTY Subsystem
 *
 * Reverse-Engineered from Termux PTY and Android terminal emulator architectures:
 * - Allocates pseudo-terminal (pty) master/slave pair
 * - Spawns PRoot process with Debian RootFS inside child process
 * - Provides window resizing (TIOCSWINSZ) and waitpid hooks
 * ==============================================================================
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pty.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "DebTerm-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jintArray JNICALL
Java_com_debterm_app_PtyBridge_createSubprocess(
        JNIEnv *env,
        jclass clazz,
        jstring cmd,
        jobjectArray args,
        jobjectArray envVars,
        jstring cwd,
        jint rows,
        jint cols) {

    int master_fd = -1;
    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };

    pid_t pid = forkpty(&master_fd, NULL, NULL, &sz);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        return NULL;
    }

    if (pid == 0) {
        // Child process inside slave PTY
        // Apply working directory
        if (cwd != NULL) {
            const char *cwd_str = (*env)->GetStringUTFChars(env, cwd, NULL);
            if (chdir(cwd_str) != 0) {
                chdir("/data/data/com.debterm.app/files");
            }
            (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);
        }

        // Apply environment variables
        if (envVars != NULL) {
            jsize env_len = (*env)->GetArrayLength(env, envVars);
            for (int i = 0; i < env_len; i++) {
                jstring ev = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
                if (ev) {
                    const char *ev_str = (*env)->GetStringUTFChars(env, ev, NULL);
                    putenv(strdup(ev_str));
                    (*env)->ReleaseStringUTFChars(env, ev, ev_str);
                }
            }
        }

        // Prepare exec arguments
        const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);
        jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
        char **argv = malloc(sizeof(char*) * (argc + 2));
        argv[0] = strdup(cmd_str);

        for (int i = 0; i < argc; i++) {
            jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
            argv[i + 1] = strdup(arg_str);
            (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        }
        argv[argc + 1] = NULL;

        // Execute proot or shell
        execvp(cmd_str, argv);

        // If exec fails:
        fprintf(stderr, "Failed to exec %s: %s\n", cmd_str, strerror(errno));
        _exit(127);
    }

    // Set non-blocking on master FD
    int flags = fcntl(master_fd, F_GETFL);
    fcntl(master_fd, F_SETFL, flags | O_NONBLOCK);

    jintArray result = (*env)->NewIntArray(env, 2);
    jint vals[2] = { (jint)pid, (jint)master_fd };
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;
}

JNIEXPORT void JNICALL
Java_com_debterm_app_PtyBridge_setWindowSize(
        JNIEnv *env,
        jclass clazz,
        jint fd,
        jint rows,
        jint cols) {
    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL
Java_com_debterm_app_PtyBridge_waitFor(
        JNIEnv *env,
        jclass clazz,
        jint pid) {
    int status = 0;
    waitpid((pid_t)pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }
    return status;
}

JNIEXPORT void JNICALL
Java_com_debterm_app_PtyBridge_closeFd(
        JNIEnv *env,
        jclass clazz,
        jint fd) {
    if (fd >= 0) {
        close(fd);
    }
}
