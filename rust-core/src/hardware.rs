// ==============================================================================
// DebTerm Hardware & Chipset Diagnostics Module (Rust)
// ==============================================================================

use std::fs;
use std::path::Path;

pub struct HardwareReport {
    pub soc_model: String,
    pub architecture: String,
    pub total_cores: usize,
    pub active_gpus: Vec<String>,
    pub memory_nodes: Vec<String>,
    pub thermal_zones: Vec<(String, i32)>,
    pub battery_info: Option<String>,
}

pub fn inspect_hardware() -> HardwareReport {
    let mut soc_model = "Unknown".to_string();
    let architecture = std::env::consts::ARCH.to_string();

    // Check cpuinfo
    if let Ok(cpuinfo) = fs::read_to_string("/proc/cpuinfo") {
        for line in cpuinfo.lines() {
            if line.starts_with("Hardware") || line.starts_with("model name") {
                if let Some(val) = line.split(':').nth(1) {
                    soc_model = val.trim().to_string();
                    break;
                }
            }
        }
    }

    // Check Android getprop / build.prop if available
    let prop_candidates = ["/vendor/build.prop", "/system/build.prop"];
    for prop in &prop_candidates {
        if let Ok(content) = fs::read_to_string(prop) {
            for line in content.lines() {
                if line.starts_with("ro.soc.model=") || line.starts_with("ro.product.model=") {
                    if let Some(val) = line.split('=').nth(1) {
                        soc_model = format!("{} ({})", val.trim(), soc_model);
                        break;
                    }
                }
            }
        }
    }

    // CPU Cores
    let total_cores = if let Ok(cpuinfo) = fs::read_to_string("/proc/cpuinfo") {
        cpuinfo.lines().filter(|l| l.starts_with("processor")).count()
    } else {
        1
    };

    // GPU Nodes
    let mut active_gpus = Vec::new();
    let gpu_nodes = [
        ("/dev/kgsl-3d0", "Qualcomm Adreno GPU (Hardware Direct)"),
        ("/dev/mali0", "ARM Mali GPU (Hardware Direct)"),
        ("/dev/mali", "ARM Mali GPU (Legacy Node)"),
        ("/dev/dri", "Direct Rendering Infrastructure (DRI/DRM)"),
        ("/dev/nvhost-gpu", "Nvidia Tegra GPU"),
        ("/dev/pvr_sync", "PowerVR GPU"),
    ];

    for (node, desc) in &gpu_nodes {
        if Path::new(node).exists() {
            active_gpus.push(format!("{} -> {}", node, desc));
        }
    }

    // Memory / Buffer Nodes
    let mut memory_nodes = Vec::new();
    let mem_nodes = [
        "/dev/ashmem",
        "/dev/ion",
        "/dev/dma_heap",
        "/dev/shm",
    ];
    for node in &mem_nodes {
        if Path::new(node).exists() {
            memory_nodes.push(node.to_string());
        }
    }

    // Thermal zones
    let mut thermal_zones = Vec::new();
    if let Ok(entries) = fs::read_dir("/sys/class/thermal") {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() && path.file_name().map_or(false, |n| n.to_string_lossy().starts_with("thermal_zone")) {
                let type_file = path.join("type");
                let temp_file = path.join("temp");
                if let (Ok(t_name), Ok(temp_str)) = (fs::read_to_string(type_file), fs::read_to_string(temp_file)) {
                    if let Ok(raw_temp) = temp_str.trim().parse::<i32>() {
                        let c_temp = if raw_temp > 1000 { raw_temp / 1000 } else { raw_temp };
                        thermal_zones.push((t_name.trim().to_string(), c_temp));
                        if thermal_zones.len() >= 6 {
                            break;
                        }
                    }
                }
            }
        }
    }

    // Battery
    let battery_info = if Path::new("/sys/class/power_supply/battery").exists() {
        let cap = fs::read_to_string("/sys/class/power_supply/battery/capacity")
            .unwrap_or_else(|_| "N/A".to_string());
        let status = fs::read_to_string("/sys/class/power_supply/battery/status")
            .unwrap_or_else(|_| "N/A".to_string());
        Some(format!("{}% (Status: {})", cap.trim(), status.trim()))
    } else {
        None
    };

    HardwareReport {
        soc_model,
        architecture,
        total_cores,
        active_gpus,
        memory_nodes,
        thermal_zones,
        battery_info,
    }
}

pub fn print_report(rep: &HardwareReport) {
    println!("\x1b[1;36m================================================================\x1b[0m");
    println!("\x1b[1;32m      DebTerm Rust Core: Hardware & Chipset Inspection          \x1b[0m");
    println!("\x1b[1;36m================================================================\x1b[0m");
    println!("\x1b[1m[1] Platform & SoC:\x1b[0m");
    println!("    SoC/Model    : \x1b[1;33m{}\x1b[0m", rep.soc_model);
    println!("    Architecture : \x1b[1;33m{}\x1b[0m", rep.architecture);
    println!("    Total Cores  : \x1b[1;33m{}\x1b[0m", rep.total_cores);

    println!("\n\x1b[1m[2] GPU & Acceleration Nodes:\x1b[0m");
    if rep.active_gpus.is_empty() {
        println!("    No hardware direct GPU nodes found (running standard DRM/software mode)");
    } else {
        for gpu in &rep.active_gpus {
            println!("    \x1b[1;32m✓ {}\x1b[0m", gpu);
        }
    }

    println!("\n\x1b[1m[3] Memory & DMA Heaps:\x1b[0m");
    for mem in &rep.memory_nodes {
        println!("    \x1b[1;32m✓ {}\x1b[0m [Available]", mem);
    }

    if !rep.thermal_zones.is_empty() {
        println!("\n\x1b[1m[4] Thermal Sensors:\x1b[0m");
        for (name, temp) in &rep.thermal_zones {
            println!("    Zone {:<15} : \x1b[1;32m{}°C\x1b[0m", name, temp);
        }
    }

    if let Some(bat) = &rep.battery_info {
        println!("\n\x1b[1m[5] Battery:\x1b[0m");
        println!("    Level: \x1b[1;32m{}\x1b[0m", bat);
    }

    println!("\x1b[1;36m================================================================\x1b[0m");
}
