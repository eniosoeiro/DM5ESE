export interface SheetReading { label: string; value: number | null; unit: string; state: string }
export interface Sheet { headers: string[]; labels: string[]; rows: string[][] }
const number = (value: number) => value.toLocaleString("pt-BR", { maximumFractionDigits: 6, useGrouping: false });
export function makeSheet(readings: SheetReading[]): Sheet {
  const matches = readings.map(r => /^(\d+)([A-Z]+)$/.exec(r.label));
  const units = new Set(readings.map(r => r.unit));
  const value = (r: SheetReading) => r.state === "OBSTR" ? "OBSTR" : r.value === null ? "" : number(r.value) + (units.size > 1 ? ` ${r.unit}` : "");
  if (matches.every(Boolean) && new Set(readings.map(r => r.label)).size === readings.length) {
    const labels = [...new Set(matches.map(m => m![1]))].sort((a, b) => Number(a) - Number(b));
    const columns = [...new Set(matches.map(m => m![2]))].sort((a, b) => a.length - b.length || a.localeCompare(b));
    if (labels.length * columns.length <= 50000) {
      const lookup = new Map(readings.map(r => [r.label, value(r)]));
      return { headers: columns, labels, rows: labels.map(row => columns.map(col => lookup.get(row + col) ?? "")) };
    }
  }
  return { headers: ["Posição", "Espessura", "Unidade", "Estado"], labels: readings.map((_, i) => String(i + 1)), rows: readings.map(r => [r.label, r.value === null ? "" : number(r.value), r.unit, ({ OK: "Válida", EMPTY: "Vazia", OBSTR: "Obstrução" } as Record<string, string>)[r.state] ?? r.state]) };
}
export function tsv(rows: string[][]): string {
  return rows.map(row => row.map(value => {
    // Keep device labels as text in Excel; preserve numeric cells.
    const safe = /^[=+@\-]/.test(value) && !/^-?\d+(,\d+)?$/.test(value) ? "'" + value : value;
    return /[\t\r\n"]/.test(safe) ? '"' + safe.replace(/"/g, '""') + '"' : safe;
  }).join("\t")).join("\r\n");
}
