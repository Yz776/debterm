// ==============================================================================
// DebTerm High-Performance Web Terminal Server (Pure Rust)
// RFC 6455 WebSocket Bridge to PRoot Debian
// ==============================================================================

use std::fs;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

use crate::proot::ProotConfig;
use crate::sha1::{base64_encode, sha1};

const WS_MAGIC_GUID: &str = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

pub fn run_server(port: u16, static_dir: PathBuf, proot_cfg: ProotConfig) {
    let addr = format!("0.0.0.0:{}", port);
    let listener = TcpListener::bind(&addr).expect("Failed to bind TCP listener");
    println!("\x1b[1;32m[+] DebTerm Rust Web Terminal running at: http://{}\x1b[0m", addr);
    println!("\x1b[1;33m[+] Pure Rust engine: ultra-low RAM (<2MB), zero dependencies, instant response.\x1b[0m");

    let proot_arc = Arc::new(proot_cfg);
    let static_arc = Arc::new(static_dir);

    for stream in listener.incoming() {
        if let Ok(stream) = stream {
            let p_cfg = Arc::clone(&proot_arc);
            let s_dir = Arc::clone(&static_arc);
            thread::spawn(move || {
                handle_client(stream, &s_dir, &p_cfg);
            });
        }
    }
}

fn handle_client(mut stream: TcpStream, static_dir: &Path, proot_cfg: &ProotConfig) {
    let mut header_buf = [0u8; 4096];
    let n = match stream.peek(&mut header_buf) {
        Ok(n) if n > 0 => n,
        _ => return,
    };

    let req_str = String::from_utf8_lossy(&header_buf[..n]).to_string();
    let mut discard = vec![0u8; n];
    let _ = stream.read_exact(&mut discard);

    // Check if WebSocket upgrade
    if req_str.contains("Upgrade: websocket") || req_str.contains("upgrade: websocket") {
        handle_websocket(stream, &req_str, proot_cfg);
    } else {
        // Handle regular HTTP GET
        handle_http(stream, &req_str, static_dir);
    }
}

fn handle_http(mut stream: TcpStream, req_str: &str, static_dir: &Path) {
    let first_line = req_str.lines().next().unwrap_or("");
    let mut path = "/index.html";
    if let Some(target) = first_line.split_whitespace().nth(1) {
        if target != "/" && !target.contains("..") {
            path = target;
        }
    }

    let file_path = static_dir.join(path.trim_start_matches('/'));
    if let Ok(content) = fs::read(&file_path) {
        let mime = if path.ends_with(".html") {
            "text/html; charset=utf-8"
        } else if path.ends_with(".css") {
            "text/css; charset=utf-8"
        } else if path.ends_with(".js") {
            "application/javascript; charset=utf-8"
        } else {
            "application/octet-stream"
        };

        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            mime,
            content.len()
        );
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.write_all(&content);
    } else {
        let response = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found";
        let _ = stream.write_all(response.as_bytes());
    }
}

fn handle_websocket(mut stream: TcpStream, req_str: &str, proot_cfg: &ProotConfig) {
    // Extract Sec-WebSocket-Key
    let mut ws_key = None;
    for line in req_str.lines() {
        if line.to_lowercase().starts_with("sec-websocket-key:") {
            if let Some(k) = line.split(':').nth(1) {
                ws_key = Some(k.trim().to_string());
                break;
            }
        }
    }

    let key = match ws_key {
        Some(k) => k,
        None => return,
    };

    // Calculate accept token
    let accept_input = format!("{}{}", key, WS_MAGIC_GUID);
    let hash = sha1(accept_input.as_bytes());
    let accept_key = base64_encode(&hash);

    // Send HTTP 101 Switching Protocols
    let handshake_resp = format!(
        "HTTP/1.1 101 Switching Protocols\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Accept: {}\r\n\r\n",
        accept_key
    );
    if stream.write_all(handshake_resp.as_bytes()).is_err() {
        return;
    }

    println!("[*] WebSocket client connected. Spawning DebTerm PRoot session...");

    // Spawn PRoot Debian process
    let mut cmd = proot_cfg.build_command(None);
    cmd.env("TERM", "xterm-256color")
       .env("COLUMNS", "100")
       .env("LINES", "30");

    #[cfg(unix)]
    {
        use std::os::unix::io::FromRawFd;
        let (master, slave) = match crate::pty::unix_pty::create_pty(30, 100) {
            Ok(pair) => pair,
            Err(_) => return,
        };

        unsafe {
            cmd.stdin(std::process::Stdio::from_raw_fd(slave));
            cmd.stdout(std::process::Stdio::from_raw_fd(slave));
            cmd.stderr(std::process::Stdio::from_raw_fd(slave));
        }

        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(_) => return,
        };

        unsafe {
            extern "C" { fn close(fd: i32) -> i32; }
            close(slave);
        }

        let master_read = unsafe { std::fs::File::from_raw_fd(master) };
        let mut master_write = unsafe { std::fs::File::from_raw_fd(master) };

        let mut stream_write = match stream.try_clone() {
            Ok(s) => s,
            Err(_) => return,
        };

        let running = Arc::new(AtomicBool::new(true));
        let r1 = Arc::clone(&running);

        // Thread 1: PTY Master -> WebSocket frames
        let t_pty_to_ws = thread::spawn(move || {
            let mut buf = [0u8; 2048];
            let mut r = master_read;
            while r1.load(Ordering::Relaxed) {
                match r.read(&mut buf) {
                    Ok(0) => break,
                    Ok(n) => {
                        let text = String::from_utf8_lossy(&buf[..n]);
                        let json = format!("{{\"type\":\"output\",\"data\":{}}}", escape_json(&text));
                        if send_ws_frame(&mut stream_write, json.as_bytes()).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });

        // Thread 2: WebSocket frames -> PTY Master
        loop {
            match read_ws_frame(&mut stream) {
                Ok(Some(data)) => {
                    if let Ok(text) = String::from_utf8(data) {
                        // Check if JSON input
                        if let Some(pos) = text.find("\"data\":") {
                            let sub = &text[pos + 7..];
                            if let Some(start_q) = sub.find('"') {
                                if let Some(end_q) = sub[start_q + 1..].rfind('"') {
                                    let raw_input = &sub[start_q + 1..start_q + 1 + end_q];
                                    let unescaped = unescape_json(raw_input);
                                    let _ = master_write.write_all(unescaped.as_bytes());
                                    continue;
                                }
                            }
                        }
                        let _ = master_write.write_all(text.as_bytes());
                    }
                }
                Ok(None) => break, // Connection closed
                Err(_) => break,
            }
        }

        running.store(false, Ordering::Relaxed);
        let _ = child.kill();
        let _ = t_pty_to_ws.join();
    }
}

fn send_ws_frame(stream: &mut TcpStream, data: &[u8]) -> std::io::Result<()> {
    let mut header = Vec::with_capacity(10);
    header.push(0x81); // Text frame, FIN bit set

    let len = data.len();
    if len <= 125 {
        header.push(len as u8);
    } else if len <= 65535 {
        header.push(126);
        header.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        header.push(127);
        header.extend_from_slice(&(len as u64).to_be_bytes());
    }

    stream.write_all(&header)?;
    stream.write_all(data)?;
    stream.flush()?;
    Ok(())
}

fn read_ws_frame(stream: &mut TcpStream) -> std::io::Result<Option<Vec<u8>>> {
    let mut b1 = [0u8; 2];
    if stream.read_exact(&mut b1).is_err() {
        return Ok(None);
    }

    let opcode = b1[0] & 0x0F;
    if opcode == 8 {
        return Ok(None); // Close frame
    }

    let masked = (b1[1] & 0x80) != 0;
    let mut payload_len = (b1[1] & 0x7F) as u64;

    if payload_len == 126 {
        let mut b2 = [0u8; 2];
        stream.read_exact(&mut b2)?;
        payload_len = u16::from_be_bytes(b2) as u64;
    } else if payload_len == 127 {
        let mut b8 = [0u8; 8];
        stream.read_exact(&mut b8)?;
        payload_len = u64::from_be_bytes(b8);
    }

    let mut mask = [0u8; 4];
    if masked {
        stream.read_exact(&mut mask)?;
    }

    let mut payload = vec![0u8; payload_len as usize];
    stream.read_exact(&mut payload)?;

    if masked {
        for (i, byte) in payload.iter_mut().enumerate() {
            *byte ^= mask[i % 4];
        }
    }

    Ok(Some(payload))
}

fn escape_json(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 10);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

fn unescape_json(s: &str) -> String {
    s.replace("\\n", "\n")
     .replace("\\r", "\r")
     .replace("\\t", "\t")
     .replace("\\\"", "\"")
     .replace("\\\\", "\\")
}
