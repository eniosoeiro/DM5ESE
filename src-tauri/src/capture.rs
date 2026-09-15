use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Connection {
    pub port: u32,
    pub baud: i32,
    #[serde(default)]
    pub data: i32,
    #[serde(default)]
    pub parity: i32,
    #[serde(default)]
    pub stop: i32,
    pub serial: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct FileEntry {
    pub index: u32,
    pub name: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Directory {
    pub connection: Connection,
    pub files: Vec<FileEntry>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Level {
    pub kind: i32,
    pub a_start: i64,
    pub a_end: i64,
    pub b_start: i64,
    pub b_end: i64,
    pub columns_alpha: bool,
    pub names: Vec<String>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Reading {
    pub index: usize,
    pub value: Option<f64>,
    pub status: u32,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub unit: String,
    #[serde(default)]
    pub state: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Capture {
    pub connection: Connection,
    pub file: FileEntry,
    pub captured_at: String,
    pub file_type: i32,
    pub receive_code: i32,
    pub levels: Vec<Level>,
    pub readings: Vec<Reading>,
}

fn alpha(mut n: i64) -> Result<String, String> {
    if !(1..=50000).contains(&n) {
        return Err("Rótulo alfabético fora do intervalo.".into());
    }
    let mut out = Vec::new();
    while n > 0 {
        n -= 1;
        out.push(b'A' + (n % 26) as u8);
        n /= 26;
    }
    out.reverse();
    Ok(String::from_utf8(out).unwrap())
}

impl Capture {
    pub fn validate(mut self) -> Result<Self, String> {
        let max_level = match self.file_type {
            1 => 1,
            2..=5 => 2,
            6 | 255 => 3,
            254 => 4,
            _ => return Err("Formato GE não suportado.".into()),
        };
        if self.levels.len() != 4 || self.readings.is_empty() || self.readings.len() > 50000 {
            return Err("Captura vazia ou estrutura incompleta.".into());
        }
        let mut sizes = Vec::new();
        for l in &self.levels {
            let a = l
                .a_end
                .checked_sub(l.a_start)
                .and_then(|v| v.checked_add(1))
                .ok_or("Dimensão inválida")?;
            let b = l
                .b_end
                .checked_sub(l.b_start)
                .and_then(|v| v.checked_add(1))
                .ok_or("Dimensão inválida")?;
            if !(1..=50000).contains(&a) || !(1..=50000).contains(&b) || !(0..=4).contains(&l.kind)
            {
                return Err("Dimensão GE fora do intervalo.".into());
            }
            if l.kind == 0 && (a != 1 || b != 1) {
                return Err("Nível indefinido com dimensão não unitária.".into());
            }
            if l.kind != 4 && b != 1 {
                return Err("Subnível inesperado no arquivo.".into());
            }
            sizes.push((a, b));
        }
        let expected = sizes
            .iter()
            .try_fold(1_i64, |v, (a, b)| v.checked_mul(*a)?.checked_mul(*b))
            .ok_or("Arquivo excessivamente grande")?;
        if expected != self.readings.len() as i64 {
            return Err(format!("Captura incompleta: estrutura prevê {expected} posições, GE retornou {}. Nenhuma medição aceita.", self.readings.len()));
        }
        if self.levels[max_level..].iter().any(|l| l.kind != 0) {
            return Err("Níveis inesperados para o tipo de arquivo.".into());
        }
        for (i, r) in self.readings.iter_mut().enumerate() {
            if r.index != i {
                return Err("Índices de leitura inconsistentes.".into());
            }
            r.state = if r.status & 0x20 != 0 {
                "EMPTY"
            } else if r.status & 0x80 != 0 {
                "OBSTR"
            } else {
                "OK"
            }
            .into();
            if r.state != "OK" {
                r.value = None;
            } else if !r.value.is_some_and(|v| v.is_finite() && v >= 0.0) {
                return Err(format!("Espessura inválida na posição {i}."));
            }
            r.unit = if r.status & 1 != 0 { "in" } else { "mm" }.into();
            let mut working = i as i64;
            let mut labels = Vec::new();
            for (level_index, l) in self.levels[..max_level].iter().enumerate() {
                let below = sizes[level_index + 1..max_level]
                    .iter()
                    .map(|(a, b)| a * b)
                    .product::<i64>();
                let offset = working / below;
                working %= below;
                let (a, b) = if l.kind == 4 {
                    if l.columns_alpha {
                        (offset / sizes[level_index].1, offset % sizes[level_index].1)
                    } else {
                        (offset % sizes[level_index].0, offset / sizes[level_index].0)
                    }
                } else {
                    (offset, 0)
                };
                let label = match l.kind {
                    0 => continue,
                    1 => (l.a_start + a).to_string(),
                    2 => alpha(l.a_start + a)?,
                    3 => l
                        .names
                        .get(a as usize)
                        .ok_or("Nome de posição ausente")?
                        .clone(),
                    4 if l.columns_alpha => format!("{}{}", l.a_start + a, alpha(l.b_start + b)?),
                    4 => format!("{}{}", alpha(l.b_start + b)?, l.a_start + a),
                    _ => unreachable!(),
                };
                labels.push(label);
            }
            r.label = labels.join(".");
        }
        Ok(self)
    }

    pub fn csv(&self) -> String {
        let mut out = String::from("\u{feff}arquivo;capturado_em_utc;porta;serie;indice;posicao;espessura;unidade;estado;status_ge\r\n");
        for r in &self.readings {
            let value = r
                .value
                .map(|v| v.to_string().replace('.', ","))
                .unwrap_or_default();
            let fields = [
                self.file.name.clone(),
                self.captured_at.clone(),
                format!("COM{}", self.connection.port),
                self.connection.serial.clone(),
                r.index.to_string(),
                r.label.clone(),
                value,
                r.unit.clone(),
                r.state.clone(),
                r.status.to_string(),
            ];
            out.push_str(
                &fields
                    .iter()
                    .map(|s| csv_cell(s))
                    .collect::<Vec<_>>()
                    .join(";"),
            );
            out.push_str("\r\n");
        }
        out
    }
}
fn csv_cell(s: &str) -> String {
    // Quoting alone does not prevent Excel formula evaluation.
    let prefix = if s.trim_start().starts_with(['=', '+', '-', '@']) {
        "'"
    } else {
        ""
    };
    format!("\"{prefix}{}\"", s.replace('"', "\"\""))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sample() -> Capture {
        serde_json::from_value(serde_json::json!({"connection":{"port":3,"baud":9600,"serial":"s"},"file":{"index":0,"name":"=1+2"},"capturedAt":"2026-09-15T00:00:00Z","fileType":2,"receiveCode":1,"levels":[{"kind":4,"aStart":1,"aEnd":2,"bStart":26,"bEnd":27,"columnsAlpha":true,"names":[]},{"kind":0,"aStart":0,"aEnd":0,"bStart":0,"bEnd":0,"columnsAlpha":false,"names":[]},{"kind":0,"aStart":0,"aEnd":0,"bStart":0,"bEnd":0,"columnsAlpha":false,"names":[]},{"kind":0,"aStart":0,"aEnd":0,"bStart":0,"bEnd":0,"columnsAlpha":false,"names":[]}],"readings":[{"index":0,"value":2.5,"status":0},{"index":1,"value":0,"status":32},{"index":2,"value":null,"status":128},{"index":3,"value":0.1,"status":1}]})).unwrap()
    }
    #[test]
    fn grid_states_and_csv() {
        let c = sample().validate().unwrap();
        assert_eq!(
            c.readings
                .iter()
                .map(|r| r.label.as_str())
                .collect::<Vec<_>>(),
            ["1Z", "1AA", "2Z", "2AA"]
        );
        assert_eq!(c.readings[1].value, None);
        assert_eq!(c.readings[2].state, "OBSTR");
        assert_eq!(c.readings[3].unit, "in");
        assert!(c.csv().contains("\"'=1+2\""));
        assert!(c.csv().contains("\"2,5\""));
    }
    #[test]
    fn numeric_columns_and_incomplete_capture() {
        let mut c = sample();
        c.levels[0].columns_alpha = false;
        let c = c.validate().unwrap();
        assert_eq!(c.readings[1].label, "Z2");
        assert_eq!(c.readings[2].label, "AA1");
        let mut c = sample();
        c.readings.pop();
        assert!(c.validate().is_err());
    }
    #[test]
    fn rejects_invalid_values_and_quotes_text() {
        let mut c = sample();
        c.readings[0].value = Some(f64::NAN);
        assert!(c.validate().is_err());
        assert_eq!(csv_cell("a;\"b\"\nc"), "\"a;\"\"b\"\"\nc\"");
    }
}
