use crate::capture::{Capture, Directory};
use base64::Engine;
use serde::de::DeserializeOwned;
use serde_json::{json, Value};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    time::Duration,
};
use tauri::State;
use tauri_plugin_dialog::DialogExt;

#[derive(Default)]
pub struct Session {
    gate: tokio::sync::Mutex<()>,
    cancelled: AtomicBool,
    directory: Mutex<Option<Directory>>,
    capture: Mutex<Option<Capture>>,
}

async fn run<T: DeserializeOwned>(session: &Session, request: Value) -> Result<T, String> {
    let bytes: Vec<u8> = include_str!("../ge-bridge.ps1")
        .encode_utf16()
        .flat_map(u16::to_le_bytes)
        .collect();
    let script = base64::engine::general_purpose::STANDARD.encode(bytes);
    let windows = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    let exe = std::path::Path::new(&windows).join("SysWOW64/WindowsPowerShell/v1.0/powershell.exe");
    let mut cmd = tokio::process::Command::new(exe);
    cmd.args([
        "-NoProfile",
        "-NonInteractive",
        "-STA",
        "-EncodedCommand",
        &script,
    ])
    .env("DM5E_REQUEST", request.to_string())
    .kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    let cancellation = async {
        loop {
            if session.cancelled.load(Ordering::SeqCst) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    };
    let output = tokio::select! {
        result = cmd.output() => result.map_err(|e| format!("Não foi possível iniciar o componente GE: {e}"))?,
        _ = tokio::time::sleep(Duration::from_secs(180)) => return Err("Tempo limite de 180 s excedido. O processo de captura foi encerrado.".into()),
        _ = cancellation => return Err("Operação cancelada. Nenhum dado parcial foi aceito.".into()),
    };
    if output.stdout.len() > 32 * 1024 * 1024 {
        return Err("Resposta GE excedeu o tamanho permitido.".into());
    }
    let body: Value = serde_json::from_slice(&output.stdout).map_err(|_| {
        format!(
            "O componente GE não retornou uma resposta válida: {}",
            String::from_utf8_lossy(&output.stderr)
                .chars()
                .take(1200)
                .collect::<String>()
        )
    })?;
    if body["ok"] != true || !output.status.success() {
        return Err(body["error"]
            .as_str()
            .unwrap_or("Falha no componente GE.")
            .to_owned());
    }
    serde_json::from_value(body["data"].clone()).map_err(|e| format!("Resposta GE incompleta: {e}"))
}

#[tauri::command]
pub async fn probe_driver(session: State<'_, Session>) -> Result<Value, String> {
    let _guard = session
        .gate
        .try_lock()
        .map_err(|_| "Já existe uma operação em andamento.")?;
    session.cancelled.store(false, Ordering::SeqCst);
    run(&session, json!({"action":"probe"})).await
}

#[tauri::command]
pub async fn connect_device(port: u32, session: State<'_, Session>) -> Result<Directory, String> {
    let _guard = session
        .gate
        .try_lock()
        .map_err(|_| "Já existe uma operação em andamento.")?;
    session.cancelled.store(false, Ordering::SeqCst);
    *session.directory.lock().map_err(|e| e.to_string())? = None;
    let available = crate::enumerate_ports()?;
    if port == 0 {
        if !available
            .iter()
            .any(|p| p.vid.as_deref() == Some("C251") && p.pid.as_deref() == Some("1705"))
        {
            return Err("O USB do DM5E (C251:1705) não foi encontrado. Confira cabo e driver; ou selecione manualmente a porta correta.".into());
        }
    } else if port > 256
        || !available
            .iter()
            .any(|p| p.name.eq_ignore_ascii_case(&format!("COM{port}")))
    {
        return Err("Porta não disponível. Atualize a lista.".into());
    }
    let directory: Directory = run(&session, json!({"action":"list","port":port})).await?;
    if directory.connection.port == 0
        || directory.files.len() > 50000
        || directory
            .files
            .iter()
            .enumerate()
            .any(|(i, f)| f.index != i as u32)
    {
        return Err("Diretório GE inválido.".into());
    }
    *session.directory.lock().map_err(|e| e.to_string())? = Some(directory.clone());
    Ok(directory)
}

#[tauri::command]
pub async fn capture_file(index: u32, session: State<'_, Session>) -> Result<Capture, String> {
    let _guard = session
        .gate
        .try_lock()
        .map_err(|_| "Já existe uma operação em andamento.")?;
    session.cancelled.store(false, Ordering::SeqCst);
    let directory = session
        .directory
        .lock()
        .map_err(|e| e.to_string())?
        .clone()
        .ok_or("Conecte e liste os arquivos primeiro.")?;
    let file = directory
        .files
        .get(index as usize)
        .ok_or("Arquivo não listado.")?;
    let raw: Result<Capture, String> = run(&session, json!({"action":"read","port":directory.connection.port,"index":file.index,"name":file.name,"serial":directory.connection.serial,"connection":directory.connection})).await;
    let capture = match raw.and_then(Capture::validate) {
        Ok(c) => c,
        Err(e) => {
            *session.directory.lock().map_err(|e| e.to_string())? = None;
            return Err(e);
        }
    };
    *session.capture.lock().map_err(|e| e.to_string())? = Some(capture.clone());
    Ok(capture)
}

#[tauri::command]
pub fn cancel_capture(session: State<'_, Session>) {
    session.cancelled.store(true, Ordering::SeqCst);
}

#[tauri::command]
pub async fn export_capture(
    format: String,
    app: tauri::AppHandle,
    session: State<'_, Session>,
) -> Result<bool, String> {
    let capture = session
        .capture
        .lock()
        .map_err(|e| e.to_string())?
        .clone()
        .ok_or("Nenhuma captura concluída para exportar.")?;
    let (extension, contents) = match format.as_str() {
        "csv" => ("csv", capture.csv().into_bytes()),
        "json" => (
            "json",
            serde_json::to_vec_pretty(&capture).map_err(|e| e.to_string())?,
        ),
        _ => return Err("Formato de exportação inválido.".into()),
    };
    tauri::async_runtime::spawn_blocking(move || {
        let Some(file) = app
            .dialog()
            .file()
            .set_file_name(format!("dm5e-captura.{extension}"))
            .add_filter(extension, &[extension])
            .blocking_save_file()
        else {
            return Ok(false);
        };
        std::fs::write(file.into_path().map_err(|e| e.to_string())?, contents)
            .map_err(|e| format!("Erro ao salvar: {e}"))?;
        Ok(true)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    #[ignore = "requires one connected physical DM5E; downloads first file and saves local exports"]
    async fn physical_device_capture() {
        let session = Session::default();
        let directory: Directory = run(&session, json!({"action":"list","port":0})).await.unwrap();
        let file = directory.files.first().expect("device has no files");
        let capture: Capture = run(&session, json!({"action":"read","port":directory.connection.port,"index":file.index,"name":file.name,"serial":directory.connection.serial})).await.unwrap();
        let capture = capture.validate().unwrap();
        let folder = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../artifacts/DM5ESE");
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::write(folder.join("captura-real.json"), serde_json::to_vec_pretty(&capture).unwrap()).unwrap();
        std::fs::write(folder.join("captura-real.csv"), capture.csv()).unwrap();
        println!("Physical capture: COM{}, {} files, file {}, {} readings", capture.connection.port, directory.files.len(), capture.file.name, capture.readings.len());
    }
    #[tokio::test]
    #[ignore = "requires the installed GE 32-bit COM component"]
    async fn installed_component_probe() {
        let result: Value = run(&Session::default(), json!({"action":"probe"}))
            .await
            .unwrap();
        assert_eq!(result["ready"], true);
        assert_eq!(result["bits"], 32);
    }
}
