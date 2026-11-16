# 🔬 Reverse Engineering (RE) & Analisis Arsitektur Internal Termux
### *Disertai Perbandingan Mendalam dengan Arsitektur PRoot Debian (DebTerm)*

---

## 1. Anatomi dan Dekonstruksi Termux

Termux bukan sekadar "aplikasi terminal", melainkan sebuah **lingkungan GNU/Linux terisolasi mini** yang berjalan di dalam *user sandbox* Android tanpa hak akses root (*no-root*). Untuk memahami cara kerjanya, kita membedah Termux ke dalam 4 lapisan arsitektur utama:

```
┌────────────────────────────────────────────────────────┐
│               1. ANDROID UI & SESSION LAYER            │
│  TermuxActivity ─── TerminalView ─── ExtraKeysView     │
└──────────────────────────┬─────────────────────────────┘
                           │ JNI (Java Native Interface)
┌──────────────────────────▼─────────────────────────────┐
│                 2. PTY ENGINE & SUBPROCESS             │
│   openpty() ─── forkpty() ─── libtermux-exec.so (PTY)  │
└──────────────────────────┬─────────────────────────────┘
                           │ System Calls & POSIX Signals
┌──────────────────────────▼─────────────────────────────┐
│             3. BINARY PREFIX & SHIM SUBSYSTEM          │
│   $PREFIX = /data/data/com.termux/files/usr            │
│   LD_PRELOAD = libtermux-exec.so (Execve Interceptor)  │
└──────────────────────────┬─────────────────────────────┘
                           │ Android Bionic Libc
┌──────────────────────────▼─────────────────────────────┐
│                 4. LINUX KERNEL SANDBOX                │
│    SELinux App Sandbox (uid 10xxx), seccomp filter     │
└────────────────────────────────────────────────────────┘
```

---

### Lapisan 1: Terminal UI & Terminal Emulator Core
* **`TerminalView` & `TerminalRenderer`**: Menggunakan komponen custom SurfaceView / Canvas untuk merender grid sel karakter teks. Setiap sel menyimpan karakter UTF-8, atribut warna ANSI (16 warna, 256 warna, hingga 24-bit TrueColor), gaya (bold, underline, reverse).
* **Terminal Emulator Buffer**: Mengimplementasikan parser standar VT100 / ANSI Escape Sequence (seperti `\x1b[2J` untuk clear screen, `\x1b[H` untuk home cursor).
* **Extra Keys Row**: Bar tombol virtual tambahan (`ESC`, `TAB`, `CTRL`, `ALT`, `▲`, `▼`, `◀`, `▶`). Ketika tombol `CTRL` aktif dan pengguna mengetik huruf `c`, aplikasi mentranslasikan karakter tersebut menjadi byte ASCII `0x03` (ETX / SIGINT).

---

### Lapisan 2: PTY Subsystem (Pseudo-Terminal)
Di sistem Unix/Linux, proses interaktif seperti Bash, Zsh, Vim, atau Htop membutuhkan file descriptor tipe **tty/pty** (bukan sekadar pipe `stdin`/`stdout` standar) agar fitur kontrol baris, *job control*, *echo suppression* (misal saat ketik password), dan sinyal kontrol (`SIGWINCH` saat ukuran layar berubah) dapat bekerja.
1. Android Java memanggil kode C via JNI: `openpty(&master_fd, &slave_fd, ...)`.
2. Master FD dihubungkan ke Java (dibaca oleh background worker thread untuk dikirim ke UI).
3. Slave FD dijadikan `STDIN_FILENO`, `STDOUT_FILENO`, dan `STDERR_FILENO` dari child process melalui `dup2()`.
4. Child process memanggil `execve()` untuk meluncurkan shell.

---

### Lapisan 3: Sistem Redirection Path & `libtermux-exec.so`
Di sistem Linux standar, path binary fundamental selalu berada di `/bin/sh`, `/usr/bin/env`, atau `/usr/bin/bash`. 
Namun di Android:
* Direktori `/bin` dan `/usr` **tidak ada** atau tidak dapat diubah oleh aplikasi biasa.
* Semua file aplikasi Termux terpaksa ditaruh di direktori private internal:
  `$PREFIX = /data/data/com.termux/files/usr`

**Bagaimana Termux memecahkan masalah skrip shell dengan shebang `#!/bin/sh`?**
Termux menciptakan pustaka C kecil bernama **`libtermux-exec.so`** yang di-inject menggunakan mekanisme `LD_PRELOAD`:
```c
// Cara kerja internal libtermux-exec:
int execve(const char *filename, char *const argv[], char *const envp[]) {
    if (strcmp(filename, "/bin/sh") == 0) {
        filename = "/data/data/com.termux/files/usr/bin/sh";
    } else if (strcmp(filename, "/usr/bin/env") == 0) {
        filename = "/data/data/com.termux/files/usr/bin/env";
    }
    return real_execve(filename, argv, envp);
}
```
Setiap kali ada binary atau skrip yang mencoba memanggil `/bin/sh`, fungsi `execve` asli dicegat dan path-nya diarahkan ke `$PREFIX/bin/sh`.

---

## 2. Keterbatasan & Kelemahan Fatal Termux Asli

Meskipun Termux sangat populer, arsitekturnya memiliki kelemahan mendasar:

| Masalah | Penyebab Teknis pada Termux | Efek ke Pengguna & Pengembang |
| :--- | :--- | :--- |
| **Bionic Libc vs GNU C Library (Glibc)** | Termux mengkompilasi semua paket menggunakan C Library bawaan Android (**Bionic**), bukan GNU Glibc. | Banyak program Linux standar gagal dikompilasi atau crash (misal: penanganan locale UTF-8, library getpwent, iconv, sys/vfs). Binary Linux `.deb` standar tidak bisa berjalan di Termux! |
| **Beban Porting Paket (`termux-packages`)** | Karena path `/data/data/...` di-hardcode saat compile time, ribuan paket harus di-patch dan di-build ulang secara manual oleh maintainer. | Paket sering tertinggal dari rilis upstream, maintainer kewalahan, banyak tool langka tidak tersedia di repo Termux. |
| **Restriksi Android 10+ (W^X SELinux)** | Kebijakan keamanan Android modern melarang pengeksekusian binary ELF langsung dari direktori aplikasi yang dapat ditulis (*writable data directory*). | Termux sering mengalami `Permission Denied` atau crash saat menjalankan binary hasil build lokal atau script tertentu. |
| **Sulit Testing di Luar Device** | Binary yang dikompilasi untuk Termux hanya bisa berjalan jika lingkungan path `/data/data/com.termux` ada. | Pengembang tidak bisa dengan mudah menguji tool mereka di Linux VPS, CI/CD pipeline, atau PC x86_64 tanpa emulator Android lengkap. |

---

## 3. Solusi Modern: Arsitektur PRoot Debian (DebTerm)

Untuk mengatasi seluruh kelemahan di atas, pendekatan **PRoot + Debian RootFS** adalah solusi paling elegan dan *future-proof*:

```
┌──────────────────────────────────────────────────────────────┐
│                    Terminal Frontend (UI)                    │
│    (Android Native WebView / xterm.js / Linux CLI / Web)     │
└──────────────────────────────┬───────────────────────────────┘
                               │ PTY Master/Slave
┌──────────────────────────────▼───────────────────────────────┐
│              PRoot Engine (Ptrace System Interceptor)        │
│  -0 (Fake Root UID 0)  │  -r /rootfs/debian  │  -b /dev /proc │
└──────────────────────────────┬───────────────────────────────┘
                               │ Syscall Translation
┌──────────────────────────────▼───────────────────────────────┐
│               Official Debian 12 (Bookworm) RootFS           │
│  - Full GNU Glibc 2.36+                                      │
│  - 60.000+ Paket Resmi Debian (APT / DPKG)                   │
│  - Path Standar Asli: /bin, /usr, /etc, /var, /root          │
└──────────────────────────────────────────────────────────────┘
```

### 1. Bagaimana PRoot Bekerja?
PRoot menggunakan fitur kernel Linux `ptrace(PTRACE_SYSCALL)`. Setiap kali proses child membuat panggilan sistem (*syscall*):
* `execve("/bin/bash")` ➡️ PRoot mencegatnya dan menerjemahkan path ke `/path/to/debian-rootfs/bin/bash`.
* `getuid()` ➡️ Jika opsi `-0` diaktifkan, PRoot mengembalikan nilai `0` (sehingga proses percaya dirinya adalah **root**).
* `chroot()`, `mount()` ➡️ Diemulasikan di memory ruang pengguna (*user space*), **tanpa membutuhkan hak akses root pada perangkat**.

### 2. Mengapa Debian PRoot Jauh Lebih Unggul untuk Testing?
1. **Akses ke 60.000+ Paket Debian Asli**:
   Tinggal jalankan `apt update && apt install python3 nodejs gcc git docker-cli curl tmux`. Tidak perlu repot mem-patch prefix path apapun!
2. **GNU Glibc Penuh**:
   Semua binary Linux standar yang dikompilasi untuk target arsitektur (misal: Debian x86_64 atau ARM64) langsung berjalan tanpa recompile.
3. **Kemudahan Testing Antar Platform**:
   Rootfs dan logika runner PRoot sama persis di:
   * **Linux VPS / PC x86_64**: Tempat kita melakukan scripting, CI/CD, dan automated testing saat ini.
   * **Android Device (ARM64 / AArch64)**: Tinggal pasang APK DebTerm, dan PRoot menjalankan rootfs yang sama!
4. **Keamanan & Isolasi**:
   PRoot berjalan murni di user-space. Tidak ada risiko merusak firmware atau partisi sistem Android.

---

## 4. Rangkuman Perbandingan

| Fitur / Karakteristik | Termux Asli | DebTerm (PRoot Debian) |
| :--- | :--- | :--- |
| **Basis C Library** | Android Bionic Libc | **GNU C Library (Glibc)** |
| **Sumber Paket Software** | Repo Termux khusus (di-compile ulang) | **Mirror Resmi Debian (60.000+ paket)** |
| **Path Sistem** | Non-standar (`/data/data/com.termux/...`) | **Standar POSIX (`/`, `/bin`, `/usr`, `/etc`)** |
| **Status Pengguna** | Non-root biasa (`u0_a123`) | **Emulated Root UID 0 (`root@debterm`)** |
| **Kebutuhan Device Root** | Tidak | **Tidak** |
| **Kompatibilitas Testing** | Hanya di Android | **Bisa diuji di VPS Linux, PC, maupun Android** |
| **Eksekusi Binary Standar** | Sering incompatible | **100% Kompatibel dengan binary Linux standar** |
