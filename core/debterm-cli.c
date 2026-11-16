/*
 * ==============================================================================
 * DebTerm Native PTY Engine & Process Spawner
 * Reference implementation equivalent to Termux's JNI PTY Subsystem
 *
 * Handles:
 *  - PTY allocation (openpty / forkpty)
 *  - Terminal window resizing (TIOCSWINSZ)
 *  - Environment variable isolation
 *  - PRoot execution with Debian rootfs
 *  - Bidirectional I/O forwarding with raw terminal mode
 * ==============================================================================
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/select.h>
#if defined(__APPLE__) || defined(__FreeBSD__)
#include <util.h>
#else
#include <pty.h>
#endif

static struct termios orig_termios;
static int stdin_is_raw = 0;
static pid_t child_pid = -1;
static int master_fd = -1;

void restore_terminal(void) {
    if (stdin_is_raw) {
        tcsetattr(STDIN_FILENO, TCSAFLUSH, &orig_termios);
        stdin_is_raw = 0;
    }
}

void set_raw_terminal(void) {
    if (!isatty(STDIN_FILENO)) return;
    if (tcgetattr(STDIN_FILENO, &orig_termios) < 0) return;

    struct termios raw = orig_termios;
    /* termux-like raw mode: disable echo, canonical mode, extended input processing, signal chars */
    raw.c_iflag &= ~(BRKINT | ICRNL | INPCK | ISTRIP | IXON);
    raw.c_oflag &= ~(OPOST);
    raw.c_cflag |= (CS8);
    raw.c_lflag &= ~(ECHO | ICANON | IEXTEN | ISIG);
    raw.c_cc[VMIN] = 1;
    raw.c_cc[VTIME] = 0;

    if (tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw) < 0) return;
    stdin_is_raw = 1;
    atexit(restore_terminal);
}

void handle_sigwinch(int sig) {
    (void)sig;
    if (master_fd >= 0 && isatty(STDIN_FILENO)) {
        struct winsize ws;
        if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0) {
            ioctl(master_fd, TIOCSWINSZ, &ws);
        }
    }
}

int main(int argc, char *argv[]) {
    char rootfs_path[1024];
    char exe_dir[1024];
    ssize_t len = readlink("/proc/self/exe", exe_dir, sizeof(exe_dir) - 1);
    if (len > 0) {
        exe_dir[len] = '\0';
        char *slash = strrchr(exe_dir, '/');
        if (slash) *slash = '\0';
    } else {
        strcpy(exe_dir, ".");
    }

    // Default rootfs location relative to core binary or env
    const char *env_rootfs = getenv("DEBTERM_ROOTFS");
    if (env_rootfs) {
        snprintf(rootfs_path, sizeof(rootfs_path), "%s", env_rootfs);
    } else {
        snprintf(rootfs_path, sizeof(rootfs_path), "%s/../rootfs/debian", exe_dir);
    }

    // Verify rootfs bash exists
    char bash_check[2048];
    snprintf(bash_check, sizeof(bash_check), "%s/bin/bash", rootfs_path);
    if (access(bash_check, X_OK) != 0) {
        fprintf(stderr, "[!] Debian rootfs not ready at: %s\n", rootfs_path);
        fprintf(stderr, "[!] Please run: %s/bootstrap.sh\n", exe_dir);
        return 1;
    }

    struct winsize ws;
    struct winsize *pws = NULL;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = 120;
    ws.ws_row = 35;
    if (getenv("COLUMNS")) ws.ws_col = atoi(getenv("COLUMNS"));
    if (getenv("LINES")) ws.ws_row = atoi(getenv("LINES"));
    if (isatty(STDIN_FILENO)) {
        if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0) {
            pws = &ws;
        }
    } else {
        pws = &ws;
    }

    setenv("PROOT_NO_SECCOMP", "1", 1);

    child_pid = forkpty(&master_fd, NULL, NULL, pws);
    if (child_pid < 0) {
        perror("forkpty");
        return 1;
    }

    if (child_pid == 0) {
        // In child process (inside PTY slave)
        const char *proot_bin = "proot";
        char *proot_args[] = {
            "proot",
            "-0",
            "-r", rootfs_path,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/pts",
            "-b", "/dev/shm",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "/bin/bash", "--login",
            NULL
        };

        execvp(proot_bin, proot_args);
        perror("execvp proot failed");
        _exit(127);
    }

    // In Parent process: forward STDIN <-> master_fd <-> STDOUT
    signal(SIGWINCH, handle_sigwinch);
    set_raw_terminal();

    fd_set rfds;
    char buf[4096];

    int stdin_eof = 0;

    while (1) {
        FD_ZERO(&rfds);
        if (!stdin_eof) FD_SET(STDIN_FILENO, &rfds);
        FD_SET(master_fd, &rfds);
        int maxfd = ((!stdin_eof && STDIN_FILENO > master_fd) ? STDIN_FILENO : master_fd) + 1;

        int ret = select(maxfd, &rfds, NULL, NULL, NULL);
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        // Host stdin -> PTY master
        if (!stdin_eof && FD_ISSET(STDIN_FILENO, &rfds)) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) {
                stdin_eof = 1;
            } else {
                if (write(master_fd, buf, n) < 0) break;
            }
        }

        // PTY master -> Host stdout
        if (FD_ISSET(master_fd, &rfds)) {
            ssize_t n = read(master_fd, buf, sizeof(buf));
            if (n <= 0) break;
            if (write(STDOUT_FILENO, buf, n) < 0) break;
        }
    }

    restore_terminal();
    close(master_fd);
    int status = 0;
    waitpid(child_pid, &status, 0);
    return WEXITSTATUS(status);
}
