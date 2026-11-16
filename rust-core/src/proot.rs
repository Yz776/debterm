// ==============================================================================
// DebTerm PRoot Command Builder & Environment Manager (Rust)
// ==============================================================================

use std::path::{Path, PathBuf};
use std::process::Command;

pub struct ProotConfig {
    pub rootfs_path: PathBuf,
    pub proot_binary: String,
}

impl ProotConfig {
    pub fn discover() -> Result<Self, String> {
        let rootfs = if let Ok(custom) = std::env::var("DEBTERM_ROOTFS") {
            PathBuf::from(custom)
        } else {
            let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("."));
            let dir = exe.parent().unwrap_or_else(|| Path::new("."));
            let candidate1 = dir.join("../rootfs/debian");
            let candidate2 = PathBuf::from("/root/debterm/rootfs/debian");
            if candidate1.exists() {
                candidate1
            } else if candidate2.exists() {
                candidate2
            } else {
                return Err("Debian rootfs not found! Please run core/bootstrap.sh".to_string());
            }
        };

        let proot = if let Some(p) = which::which("proot") {
            p.to_string_lossy().to_string()
        } else if Path::new("/usr/bin/proot").exists() {
            "/usr/bin/proot".to_string()
        } else {
            "proot".to_string()
        };

        Ok(ProotConfig {
            rootfs_path: rootfs,
            proot_binary: proot,
        })
    }

    pub fn build_command(&self, custom_command: Option<&[&str]>) -> Command {
        // Prevent kernel seccomp ptrace trap
        std::env::set_var("PROOT_NO_SECCOMP", "1");

        let mut cmd = Command::new(&self.proot_binary);
        cmd.arg("-0")
            .arg("-r")
            .arg(&self.rootfs_path)
            .arg("-w")
            .arg("/root")
            .arg("-b")
            .arg("/dev")
            .arg("-b")
            .arg("/proc")
            .arg("-b")
            .arg("/sys")
            .arg("-b")
            .arg("/dev/pts")
            .arg("-b")
            .arg("/dev/shm");

        // Hardware GPU & memory binds
        let hw_nodes = [
            "/dev/kgsl-3d0",
            "/dev/mali0",
            "/dev/mali",
            "/dev/dri",
            "/dev/nvhost-gpu",
            "/dev/pvr_sync",
            "/dev/ashmem",
            "/dev/ion",
            "/dev/dma_heap",
            "/dev/graphics",
        ];
        for node in &hw_nodes {
            if Path::new(node).exists() {
                cmd.arg("-b").arg(node);
            }
        }

        // Android Host filesystem & SoC bindings
        let android_dirs = [
            "/system",
            "/vendor",
            "/apex",
            "/sys/devices/system/cpu",
            "/sys/class/thermal",
            "/sys/class/power_supply",
        ];
        for dir in &android_dirs {
            if Path::new(dir).exists() {
                cmd.arg("-b").arg(dir);
            }
        }

        if Path::new("/sdcard").exists() {
            cmd.arg("-b").arg("/sdcard:/sdcard");
        }
        if let Ok(home) = std::env::var("HOME") {
            if Path::new(&home).exists() {
                cmd.arg("-b").arg(format!("{}:/root/host-home", home));
            }
        }

        // Environment isolation
        cmd.arg("/usr/bin/env")
            .arg("-i")
            .arg("HOME=/root")
            .arg("USER=root")
            .arg("LOGNAME=root")
            .arg("TERM=xterm-256color")
            .arg("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            .arg("LANG=en_US.UTF-8")
            .arg("LC_ALL=en_US.UTF-8")
            .arg("SHELL=/bin/bash");

        match custom_command {
            Some(args) => {
                cmd.args(args);
            }
            None => {
                cmd.arg("/bin/bash").arg("--login");
            }
        }

        cmd
    }
}

// Simple fallback `which` helper
mod which {
    use std::path::{Path, PathBuf};
    pub fn which(name: &str) -> Option<PathBuf> {
        if let Ok(paths) = std::env::var("PATH") {
            for p in std::env::split_paths(&paths) {
                let candidate = p.join(name);
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
        if Path::new(name).is_file() {
            return Some(PathBuf::from(name));
        }
        None
    }
}
