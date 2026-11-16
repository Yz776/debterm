// ==============================================================================
// DebTerm High-Performance PTY Subsystem (Rust)
// ==============================================================================

#[cfg(unix)]
pub mod unix_pty {
    use std::io::{self, Read, Write};
    use std::os::unix::io::{FromRawFd, RawFd};
    use std::process::Command;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;

    #[repr(C)]
    #[derive(Debug, Copy, Clone)]
    pub struct Winsize {
        pub ws_row: u16,
        pub ws_col: u16,
        pub ws_xpixel: u16,
        pub ws_ypixel: u16,
    }

    extern "C" {
        fn openpty(
            amaster: *mut i32,
            aslave: *mut i32,
            name: *mut u8,
            termp: *const std::ffi::c_void,
            winp: *const Winsize,
        ) -> i32;
        fn ioctl(fd: i32, request: u64, ...) -> i32;
        fn tcgetattr(fd: i32, termios_p: *mut std::ffi::c_void) -> i32;
        fn tcsetattr(fd: i32, optional_actions: i32, termios_p: *const std::ffi::c_void) -> i32;
    }

    pub fn create_pty(rows: u16, cols: u16) -> io::Result<(RawFd, RawFd)> {
        let mut master = -1;
        let mut slave = -1;
        let ws = Winsize {
            ws_row: rows,
            ws_col: cols,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let res = unsafe { openpty(&mut master, &mut slave, std::ptr::null_mut(), std::ptr::null(), &ws) };
        if res != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok((master, slave))
    }

    pub fn set_winsize(master_fd: RawFd, rows: u16, cols: u16) {
        let ws = Winsize {
            ws_row: rows,
            ws_col: cols,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        // TIOCSWINSZ is 0x5414 on Linux x86_64/arm64
        const TIOCSWINSZ: u64 = 0x5414;
        unsafe {
            ioctl(master_fd, TIOCSWINSZ, &ws);
        }
    }

    pub fn run_interactive(mut cmd: Command) -> io::Result<i32> {
        let (master, slave) = create_pty(30, 100)?;

        unsafe {
            cmd.stdin(std::process::Stdio::from_raw_fd(slave));
            cmd.stdout(std::process::Stdio::from_raw_fd(slave));
            cmd.stderr(std::process::Stdio::from_raw_fd(slave));
        }

        let mut child = cmd.spawn()?;
        // Slave is now owned by child, close in parent
        unsafe { libc_close(slave); }

        let running = Arc::new(AtomicBool::new(true));
        let r1 = Arc::clone(&running);

        // Background thread: Master PTY -> Stdout
        let master_read = unsafe { std::fs::File::from_raw_fd(master) };
        let mut master_write = unsafe { std::fs::File::from_raw_fd(master) };

        let t_out = std::thread::spawn(move || {
            let mut buf = [0u8; 4096];
            let mut reader = master_read;
            let mut stdout = std::io::stdout();
            while r1.load(Ordering::Relaxed) {
                match reader.read(&mut buf) {
                    Ok(0) => break,
                    Ok(n) => {
                        let _ = stdout.write_all(&buf[..n]);
                        let _ = stdout.flush();
                    }
                    Err(_) => break,
                }
            }
        });

        // Stdin -> Master PTY
        let mut stdin = std::io::stdin();
        let mut buf = [0u8; 4096];
        loop {
            match stdin.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if master_write.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }

        running.store(false, Ordering::Relaxed);
        let status = child.wait()?;
        let _ = t_out.join();

        Ok(status.code().unwrap_or(0))
    }

    unsafe fn libc_close(fd: i32) {
        extern "C" { fn close(fd: i32) -> i32; }
        close(fd);
    }
}
