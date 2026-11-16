// ==============================================================================
// DebTerm - High Performance Rust Engine & System Manager
// Fast, memory-safe, ultra-low RAM (<2MB), cross-platform Linux/Android/Windows
// ==============================================================================

mod hardware;
mod proot;
mod pty;
mod server;
mod sha1;

use std::env;
use std::path::{Path, PathBuf};
use std::process::exit;

fn main() {
    let args: Vec<String> = env::args().collect();
    let command = if args.len() > 1 { args[1].as_str() } else { "cli" };

    match command {
        "cli" | "terminal" => {
            run_cli();
        }
        "run" | "exec" => {
            if args.len() < 3 {
                eprintln!("Usage: debterm run \"<command>\"");
                exit(1);
            }
            let cmd_str = &args[2];
            run_cmd(cmd_str);
        }
        "server" | "web" => {
            let mut port: u16 = 8080;
            if args.len() >= 3 {
                if let Ok(p) = args[2].parse::<u16>() {
                    port = p;
                }
            }
            run_web_server(port);
        }
        "hardware" | "hw" | "chipset" | "gpu" => {
            let rep = hardware::inspect_hardware();
            hardware::print_report(&rep);
        }
        "help" | "--help" | "-h" => {
            print_help();
        }
        _ => {
            // If unknown argument, treat as command to run inside Debian
            let cmd_str = args[1..].join(" ");
            run_cmd(&cmd_str);
        }
    }
}

fn run_cli() {
    let proot_cfg = match proot::ProotConfig::discover() {
        Ok(cfg) => cfg,
        Err(e) => {
            eprintln!("\x1b[1;31m[-] {}\x1b[0m", e);
            exit(1);
        }
    };

    let cmd = proot_cfg.build_command(None);
    #[cfg(unix)]
    {
        match pty::unix_pty::run_interactive(cmd) {
            Ok(code) => exit(code),
            Err(e) => {
                eprintln!("PTY execution error: {}", e);
                exit(1);
            }
        }
    }
    #[cfg(not(unix))]
    {
        eprintln!("Interactive PTY console requires Unix or WSL environment.");
    }
}

fn run_cmd(cmd_str: &str) {
    let proot_cfg = match proot::ProotConfig::discover() {
        Ok(cfg) => cfg,
        Err(e) => {
            eprintln!("\x1b[1;31m[-] {}\x1b[0m", e);
            exit(1);
        }
    };

    let args = ["/bin/bash", "-c", cmd_str];
    let mut cmd = proot_cfg.build_command(Some(&args));

    let status = cmd.status().unwrap_or_else(|e| {
        eprintln!("Failed to execute command: {}", e);
        exit(1);
    });

    exit(status.code().unwrap_or(0));
}

fn run_web_server(port: u16) {
    let proot_cfg = match proot::ProotConfig::discover() {
        Ok(cfg) => cfg,
        Err(e) => {
            eprintln!("\x1b[1;31m[-] {}\x1b[0m", e);
            exit(1);
        }
    };

    let exe = env::current_exe().unwrap_or_else(|_| PathBuf::from("."));
    let exe_dir = exe.parent().unwrap_or_else(|| Path::new("."));
    let candidates = [
        exe_dir.join("../web/public"),
        PathBuf::from("/root/debterm/web/public"),
        PathBuf::from("web/public"),
    ];

    let static_dir = candidates
        .into_iter()
        .find(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from("/root/debterm/web/public"));

    server::run_server(port, static_dir, proot_cfg);
}

fn print_help() {
    println!("\x1b[1;36m================================================================\x1b[0m");
    println!("\x1b[1;32m   DebTerm Core (Rust Engine) v2.0 - Termux Architecture       \x1b[0m");
    println!("\x1b[1;36m================================================================\x1b[0m");
    println!("Usage:");
    println!("  debterm                          Start interactive Debian PRoot terminal (default)");
    println!("  debterm cli                      Start interactive PTY terminal");
    println!("  debterm run \"<command>\"          Execute command inside Debian PRoot and exit");
    println!("  debterm server [port]            Start ultra-fast Rust Web Terminal server (default 8080)");
    println!("  debterm hardware                 Inspect CPU, GPU nodes, thermal & battery");
    println!("  debterm help                     Display this help menu");
    println!("\x1b[1;36m================================================================\x1b[0m");
}
