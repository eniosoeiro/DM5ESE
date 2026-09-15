import { invoke, isTauri } from "@tauri-apps/api/core";
import "./styles.css";
import { makeSheet, tsv, type Sheet } from "./sheet";

interface Port { name: string; kind: string; product: string | null; vid: string | null; pid: string | null }
interface Connection { port: number; baud: number; serial: string }
interface Directory { connection: Connection; files: { index: number; name: string }[] }
interface Reading { index: number; label: string; value: number | null; unit: string; state: string; status: number }
interface Capture { file: { name: string }; capturedAt: string; connection: Connection; readings: Reading[] }
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const button = (id: string) => el<HTMLButtonElement>(id);
const port = el<HTMLSelectElement>("port");
const files = el<HTMLSelectElement>("files");
let busy = false;
let cancellable = false;
let driverReady = false;
let directory: Directory | null = null;
let capture: Capture | null = null;
let page = 0;
let sheet: Sheet = { headers: [], labels: [], rows: [] };
let anchor: [number, number] | null = null;
let selection: [number, number] | null = null;
const pageSize = 100;
const logs: string[] = [];

function message(text: string) {
  el("status").textContent = text;
  logs.push(`${new Date().toLocaleTimeString("pt-BR")}  ${text}`);
  el("log").textContent = logs.slice(-100).join("\n");
}
function controls() {
  for (const id of ["refresh", "export"]) button(id).disabled = busy || !isTauri();
  button("connect").disabled = busy || !driverReady;
  button("cancel").disabled = !busy || !cancellable || !isTauri();
  button("capture").disabled = busy || !directory || !files.value;
  for (const id of ["csv", "json"]) button(id).disabled = busy || !capture;
  button("copy-all").disabled = !capture;
  button("copy-selection").disabled = !selection;
  port.disabled = busy || !isTauri();
  files.disabled = busy || !directory?.files.length;
  button("previous").disabled = !capture || page === 0;
  button("next").disabled = !capture || (page + 1) * pageSize >= sheet.rows.length;
}
function clearDirectory() {
  directory = null;
  files.replaceChildren(new Option("Nenhum arquivo listado", ""));
  el("connection-info").textContent = "Conecte para consultar os arquivos salvos.";
}
async function operation(text: string, action: () => Promise<void>, canCancel = false) {
  if (busy) return;
  busy = true; cancellable = canCancel; controls(); message(text);
  try { await action(); } catch (error) { message(String(error)); }
  finally { busy = false; controls(); }
}
async function refreshPorts() {
  clearDirectory();
  const selected = port.value;
  const ports = await invoke<Port[]>("list_ports");
  port.replaceChildren(new Option("Automática · USB DM5E", "0"));
  el("ports").replaceChildren();
  for (const p of ports) {
    const match = /^COM(\d+)$/i.exec(p.name);
    if (match) port.add(new Option(`${p.name} · ${p.product ?? p.kind}`, match[1]));
    const card = document.createElement("article"); card.className = "port";
    const title = document.createElement("strong"); title.textContent = p.name;
    const detail = document.createElement("p"); detail.textContent = `${p.product ?? p.kind}${p.vid ? ` · USB ${p.vid}:${p.pid}` : ""}`;
    card.append(title, detail); el("ports").append(card);
  }
  if ([...port.options].some(p => p.value === selected)) port.value = selected;
  el("updated").textContent = `Última consulta: ${new Date().toLocaleTimeString("pt-BR")}`;
  message(`${ports.length} porta(s) disponível(is). Selecione a conexão do medidor.`);
}
function showCapture() {
  const body = el("readings"); body.replaceChildren();
  if (!capture) return;
  const valid = capture.readings.filter(r => r.state === "OK").length;
  el("capture-info").textContent = `${capture.file.name} · COM${capture.connection.port} · ${new Date(capture.capturedAt).toLocaleString("pt-BR")} · ${valid} medições / ${capture.readings.length} posições. Esta captura permanece disponível até uma nova leitura concluída.`;
  sheet = makeSheet(capture.readings);
  anchor = selection = null;
  const header = document.createElement("tr");
  for (const name of ["", ...sheet.headers]) { const cell = document.createElement("th"); cell.textContent = name; cell.scope = "col"; header.append(cell); }
  el("sheet-head").replaceChildren(header);
  const units = [...new Set(capture.readings.map(r => r.unit))].join(" / ");
  el("sheet-name").textContent = `${capture.file.name} · Espessuras (${units})`;
  el("selection-info").textContent = "Clique ou arraste para selecionar · Shift + clique amplia · Ctrl+C copia";
  for (const [offset, values] of sheet.rows.slice(page * pageSize, (page + 1) * pageSize).entries()) {
    const ri = page * pageSize + offset;
    const row = document.createElement("tr");
    const heading = document.createElement("th"); heading.textContent = sheet.labels[ri]; heading.scope = "row"; row.append(heading);
    for (const [ci, text] of values.entries()) {
      const td = document.createElement("td"); td.textContent = text; td.dataset.row = String(ri); td.dataset.col = String(ci);
      td.title = `${sheet.labels[ri]}${sheet.headers[ci]}: ${text || "Vazia"}`;
      row.append(td);
    }
    body.append(row);
  }
  el("page").textContent = `${sheet.rows.length} linhas · ${sheet.headers.length} colunas · Página ${page + 1} de ${Math.ceil(sheet.rows.length / pageSize)}`;
  controls();
}
button("refresh").onclick = () => void operation("Consultando portas…", refreshPorts);
port.onchange = () => { clearDirectory(); controls(); };
files.onchange = controls;
button("connect").onclick = () => void operation("Conectando e consultando arquivos. Aguarde…", async () => {
  clearDirectory();
  directory = await invoke<Directory>("connect_device", { port: Number(port.value) });
  files.replaceChildren();
  for (const f of directory.files) files.add(new Option(f.name || `Arquivo ${f.index}`, f.index.toString()));
  if (!directory.files.length) files.add(new Option("Datalogger sem arquivos", ""));
  el("connection-info").textContent = `Última conexão confirmada: COM${directory.connection.port} · ${directory.connection.baud} baud${directory.connection.serial ? ` · Série ${directory.connection.serial}` : ""}. A conexão será verificada novamente na captura.`;
  message(`DM5E respondeu. ${directory.files.length} arquivo(s) encontrado(s).`);
}, true);
button("capture").onclick = () => void operation("Baixando arquivo do DM5E. Aguarde…", async () => {
  try {
    const result = await invoke<Capture>("capture_file", { index: Number(files.value) });
    capture = result; page = 0; showCapture();
    message(`Captura concluída: ${result.readings.length} posições. CSV e JSON disponíveis.`);
  } catch (error) { clearDirectory(); throw error; }
}, true);
button("cancel").onclick = async () => {
  button("cancel").disabled = true;
  try { await invoke("cancel_capture"); } catch (error) { message(String(error)); }
};
for (const format of ["csv", "json"]) {
  button(format).onclick = () => void operation("Escolha onde salvar a captura…", async () => {
    const saved = await invoke<boolean>("export_capture", { format });
    message(saved ? `Captura exportada em ${format.toUpperCase()}.` : "Exportação cancelada.");
  });
}
button("export").onclick = () => void operation("Escolha onde salvar o diagnóstico…", async () => {
  const saved = await invoke<boolean>("export_diagnostics"); message(saved ? "Diagnóstico de portas salvo." : "Exportação cancelada.");
});
button("previous").onclick = () => { if (page > 0) page--; showCapture(); };
button("next").onclick = () => { if (capture && (page+1)*pageSize < sheet.rows.length) page++; showCapture(); };

function bounds() {
  if (!anchor || !selection) return null;
  return [Math.min(anchor[0], selection[0]), Math.max(anchor[0], selection[0]), Math.min(anchor[1], selection[1]), Math.max(anchor[1], selection[1])];
}
function highlight() {
  const range = bounds();
  for (const cell of el("readings").querySelectorAll<HTMLTableCellElement>("td")) {
    const r = Number(cell.dataset.row), c = Number(cell.dataset.col);
    cell.classList.toggle("selected", !!range && r >= range[0] && r <= range[1] && c >= range[2] && c <= range[3]);
  }
  if (range) el("selection-info").textContent = `${range[1] - range[0] + 1} linha(s) × ${range[3] - range[2] + 1} coluna(s) selecionadas · Ctrl+C para copiar`;
  controls();
}
let dragging = false;
el("readings").onpointerdown = event => {
  const cell = (event.target as HTMLElement).closest<HTMLTableCellElement>("td[data-row]");
  if (!cell || event.button !== 0) return;
  event.preventDefault(); el("sheet-scroll").focus({ preventScroll: true });
  selection = [Number(cell.dataset.row), Number(cell.dataset.col)];
  if (!event.shiftKey || !anchor) anchor = selection;
  dragging = true; highlight();
};
el("readings").onpointerover = event => {
  const cell = (event.target as HTMLElement).closest<HTMLTableCellElement>("td[data-row]");
  if (!dragging || !cell) return;
  selection = [Number(cell.dataset.row), Number(cell.dataset.col)]; highlight();
};
window.addEventListener("pointerup", () => { dragging = false; });
window.addEventListener("blur", () => { dragging = false; });
async function copySheet(all: boolean) {
  if (!capture) return;
  const range = bounds();
  if (!all && !range) return;
  const rows = all ? [["Posição", ...sheet.headers], ...sheet.rows.map((row, i) => [sheet.labels[i], ...row])] : sheet.rows.slice(range![0], range![1] + 1).map(row => row.slice(range![2], range![3] + 1));
  const text = tsv(rows);
  try {
    try { await navigator.clipboard.writeText(text); }
    catch {
      const area = document.createElement("textarea"); area.value = text; area.style.position = "fixed"; area.style.opacity = "0"; document.body.append(area); area.select();
      try { if (!document.execCommand("copy")) throw new Error("Não foi possível acessar a área de transferência."); }
      finally { area.remove(); }
    }
    el("copy-status").textContent = `${all ? "Planilha copiada" : "Seleção copiada"}. Cole no Excel com Ctrl+V.`;
  } catch (error) { el("copy-status").textContent = String(error); }
}
button("copy-all").onclick = () => void copySheet(true);
button("copy-selection").onclick = () => void copySheet(false);
el("sheet-scroll").onkeydown = event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "c") { event.preventDefault(); void copySheet(!selection); }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "a" && sheet.rows.length) { event.preventDefault(); anchor = [0, 0]; selection = [sheet.rows.length - 1, sheet.headers.length - 1]; highlight(); }
  if (event.key === "Escape") { anchor = selection = null; highlight(); }
};

controls();
if (isTauri()) {
  void operation("Preparando comunicação GE…", async () => {
    await refreshPorts();
    try {
      const result = await invoke<{ driver: string }>("probe_driver");
      driverReady = true; el("driver-status").textContent = `Componente GE disponível: ${result.driver}. Pronto para testar o instrumento.`;
    } catch (error) { el("driver-status").textContent = `Componente GE indisponível. Instale o pacote DM5EExcelMacro. ${String(error)}`; }
  });
} else {
  message("Prévia no navegador. Use o executável desktop para capturar dados.");
  el("driver-status").textContent = "A comunicação GE está disponível no aplicativo Windows.";
}
