use serde::Serialize;
use serialport::SerialPortType;
use tauri_plugin_dialog::DialogExt;
mod bridge;
mod capture;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PortInfo {
    name: String,
    kind: String,
    manufacturer: Option<String>,
    product: Option<String>,
    serial_number: Option<String>,
    vid: Option<String>,
    pid: Option<String>,
}

fn enumerate_ports() -> Result<Vec<PortInfo>, String> {
    let mut ports: Vec<PortInfo> = serialport::available_ports()
        .map_err(|e| format!("Não foi possível consultar as portas: {e}"))?
        .into_iter()
        .map(|port| {
            let mut info = PortInfo {
                name: port.port_name,
                kind: "Outra / não identificada".into(),
                manufacturer: None,
                product: None,
                serial_number: None,
                vid: None,
                pid: None,
            };
            match port.port_type {
                SerialPortType::UsbPort(usb) => {
                    info.kind = "USB".into();
                    info.manufacturer = usb.manufacturer;
                    info.product = usb.product;
                    info.serial_number = usb.serial_number;
                    info.vid = Some(format!("{:04X}", usb.vid));
                    info.pid = Some(format!("{:04X}", usb.pid));
                }
                SerialPortType::BluetoothPort => info.kind = "Bluetooth".into(),
                SerialPortType::PciPort => info.kind = "PCI / integrada".into(),
                SerialPortType::Unknown => {}
            }
            info
        })
        .collect();
    ports.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(ports)
}

#[tauri::command]
async fn list_ports() -> Result<Vec<PortInfo>, String> {
    tauri::async_runtime::spawn_blocking(enumerate_ports)
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn export_diagnostics(app: tauri::AppHandle) -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let ports = enumerate_ports()?;
        let report = serde_json::json!({
            "application": "DM5ESE",
            "version": env!("CARGO_PKG_VERSION"),
            "timestampUnixSeconds": std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH).map_err(|e| e.to_string())?.as_secs(),
            "platform": std::env::consts::OS,
            "ports": ports,
            "protocolStatus": "ge_com_bridge_experimental",
            "note": "Inventário de portas. Não confirma a identidade ou a comunicação com o DM5E."
        });
        let Some(file) = app
            .dialog()
            .file()
            .set_file_name("dm5ese-diagnostico.json")
            .add_filter("Diagnóstico JSON", &["json"])
            .blocking_save_file()
        else {
            return Ok(false);
        };
        let path = file.into_path().map_err(|e| e.to_string())?;
        let contents = serde_json::to_vec_pretty(&report).map_err(|e| e.to_string())?;
        std::fs::write(path, contents).map_err(|e| format!("Erro ao salvar diagnóstico: {e}"))?;
        Ok(true)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(bridge::Session::default())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            list_ports,
            export_diagnostics,
            bridge::probe_driver,
            bridge::connect_device,
            bridge::capture_file,
            bridge::cancel_capture,
            bridge::export_capture
        ])
        .run(tauri::generate_context!())
        .expect("Erro ao iniciar DM5ESE");
}
