package com.dm5ese.usbprobe;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** File reads and empty-grid creation observed on DM5E firmware 01.24, export 2.1. */
public final class Dm5eProtocol {
    public interface Link {
        void write(String ascii) throws IOException;
        byte[] readLine() throws IOException;
        default void readAck() throws IOException { throw new IOException("Transporte sem suporte a envio."); }
        default void discardInput() throws IOException { }
        default void awaitDeletion() throws IOException { throw new IOException("Exclusão não suportada pelo transporte."); }
    }
    public record FileEntry(int number, String name) {
        @Override public String toString() { return name; }
    }
    public record Reading(String position, String value, String unit, String raw) { }
    public record Capture(FileEntry file, Map<String, String> metadata, List<Reading> readings,
                          List<String> rawFrames) { }
    private final Link link;
    public Dm5eProtocol(Link link) { this.link = link; }

    public record NewGrid(String name, int rows, int columns) {
        public NewGrid {
            if (name == null || !name.matches("[A-Z0-9_-]{1,15}"))
                throw new IllegalArgumentException("Nome: 1 a 15 caracteres A-Z, 0-9, _ ou -.");
            if (rows < 1 || columns < 1 || columns > 26 || (long) rows * columns > 100)
                throw new IllegalArgumentException("Use 1 a 26 colunas e no maximo 100 pontos nesta versao.");
        }
        List<String> records() {
            List<String> out = new ArrayList<>();
            out.add("\u00ff".repeat(50)); out.add(pad("2", 5));
            out.add(pad(Integer.toString(rows), 3)); out.add(pad("1", 3));
            out.add(pad(Integer.toString(columns), 3)); out.add(pad("1", 3)); out.add("LR");
            for (int r = 1; r <= rows; r++) for (int c = 1; c <= columns; c++)
                out.add(pad(Integer.toString(r), 3) + pad(alpha(c), 3) + "\u00ff".repeat(33));
            return out;
        }
        private static String pad(String s, int width) { return "\u00ff".repeat(width - s.length()) + s; }
    }
    /** Never retries FU: an interrupted upload may already have created a file. */
    public Capture create(NewGrid grid) throws IOException {
        List<FileEntry> before = directory();
        for (FileEntry f : before) if (f.name.equalsIgnoreCase(grid.name))
            throw new IOException("Ja existe um arquivo com esse nome. Escolha outro nome.");
        try {
            link.write("\u001bFU " + grid.name + "\r"); link.readAck();
            for (String record : grid.records()) { link.write(record + "\r\n"); link.readAck(); }
            // The preceding record supplied LF; do not leave an extra LF after completion.
            link.write("[D2TE] 000\r"); link.readAck();
            List<FileEntry> after = directory();
            Set<String> names = new HashSet<>(); for (FileEntry f : after) names.add(f.name);
            if (after.size() != before.size() + 1 || before.stream().anyMatch(f -> !names.contains(f.name)))
                throw new IOException("Lista apos envio diferente da esperada.");
            FileEntry created = after.stream().filter(f -> f.name.equals(grid.name)).findFirst()
                .orElseThrow(() -> new IOException("Arquivo enviado nao apareceu na lista."));
            Capture result = download(created);
            if (!Integer.toString(grid.rows).equals(result.metadata.get("L2NL"))
                || !Integer.toString(grid.columns).equals(result.metadata.get("L3NL"))
                || result.readings.stream().anyMatch(r -> !r.value.isEmpty()))
                throw new IOException("Conteudo recebido diferente da matriz vazia solicitada.");
            return result;
        } catch (IOException e) {
            throw new IOException("Criacao nao confirmada. O arquivo pode existir ou estar incompleto no DM5E. "
                + "Nao repita o envio automaticamente; confira o instrumento e liste novamente. " + e.getMessage(), e);
        }
    }

    public record DeleteResult(String deleted, List<FileEntry> remaining) { }
    /** One transmission per selected name; stop the batch on any uncertain result. */
    public DeleteResult deleteFiles(List<String> selected, List<FileEntry> displayed) throws IOException {
        return deleteFiles(selected, displayed, message -> { });
    }
    public DeleteResult deleteFiles(List<String> selected, List<FileEntry> displayed,
                                    java.util.function.Consumer<String> progress) throws IOException {
        List<String> targets = List.copyOf(selected);
        if (targets.isEmpty() || new HashSet<>(targets).size() != targets.size())
            throw new IOException("Seleção vazia ou com nomes duplicados.");
        Set<String> expected = new HashSet<>();
        for (FileEntry file : displayed) if (!expected.add(file.name)) throw new IOException("Lista com nomes duplicados.");
        for (String name : targets)
            if (!name.matches("[A-Za-z0-9 _-]{1,16}") || !expected.contains(name))
                throw new IOException("Nome inválido ou ausente na lista apresentada.");
        List<FileEntry> before = readDirectory();
        Set<String> current = new HashSet<>(); for (FileEntry file : before) current.add(file.name);
        if (before.size() != expected.size() || !current.equals(expected))
            throw new IOException("A lista mudou. Conecte e liste novamente antes de apagar.");
        List<String> confirmed = new ArrayList<>();
        List<FileEntry> after = before;
        for (String name : targets) {
            try {
                progress.accept("Apagando " + (confirmed.size() + 1) + " de " + targets.size() + ": " + name + ". Aguarde…");
                link.write("\u001bDF " + name + "\r");
                link.awaitDeletion();
                after = readDirectory();
                expected.remove(name); current.clear(); for (FileEntry file : after) current.add(file.name);
                if (after.size() != expected.size() || !current.equals(expected))
                    throw new IOException("A lista recebida não confirma a exclusão esperada.");
                confirmed.add(name);
                progress.accept("Confirmados " + confirmed.size() + " de " + targets.size() + ": " + String.join(", ", confirmed));
            } catch (IOException e) {
                throw new IOException("Exclusão interrompida. Confirmados: " + (confirmed.isEmpty() ? "nenhum" : String.join(", ", confirmed))
                    + ". Não foi possível confirmar " + name + "; ele pode já ter sido apagado. Os demais não serão enviados."
                    + " Nenhum comando será repetido. Confira o DM5E e liste novamente. " + e.getMessage(), e);
            }
        }
        return new DeleteResult(String.join(", ", confirmed), List.copyOf(after));
    }

    private String command(String command) throws IOException {
        link.write("\u001b" + command + "\r");
        return new String(link.readLine(), StandardCharsets.ISO_8859_1);
    }
    public void identify() throws IOException {
        if (!"DM5E".equals(command("ID"))) throw new IOException("O equipamento não respondeu como DM5E.");
    }
    public List<FileEntry> directory() throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return readDirectory(); }
            catch (IOException e) { failure = e; if (attempt < 2) link.discardInput(); }
        }
        throw new IOException("Não foi possível receber uma lista íntegra após 3 tentativas. "
            + "Reconecte o cabo USB e toque em Conectar e listar arquivos. "
            + "Se o DM5E estiver travado, reinicie o instrumento antes de tentar novamente.", failure);
    }
    /** Read-only fallback for instruments whose unpaced DR stream loses bytes. */
    public List<FileEntry> directoryWithRecovery(java.util.function.Consumer<String> progress) throws IOException {
        try { return directory(); }
        catch (IOException failedStream) {
            progress.accept("Lista USB inconsistente. Consultando arquivos individualmente com verificação…");
            link.discardInput();
            identify();
            int count = number(command("DL"), 0, 50000);
            List<FileEntry> result = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (int index = 1; index <= count; index++) {
                progress.accept("Conferindo arquivo " + index + " de " + count + "…");
                Capture header = download(new FileEntry(index, ""), progress, true);
                String name = required(header.metadata(), "FLNM");
                if (name.isEmpty() || name.length() > 24 || !names.add(name))
                    throw new IOException("Nome ausente ou duplicado na consulta individual. Lista descartada.");
                result.add(new FileEntry(index, name));
            }
            if (number(command("DL"), 0, 50000) != count)
                throw new IOException("A quantidade de arquivos mudou durante a consulta. Liste novamente.");
            return List.copyOf(result);
        }
    }
    private List<FileEntry> readDirectory() throws IOException {
        identify();
        int count = number(command("DL"), 0, 50000);
        List<FileEntry> result = new ArrayList<>();
        if (count == 0) return result;
        link.write("\u001bDR\r");
        for (int i = 1; i <= count; i++) {
            String line = new String(link.readLine(), StandardCharsets.ISO_8859_1);
            if (!line.matches("[0-9]{4} .{1,24}") || number(line.substring(0, 4), 1, 50000) != i)
                throw new IOException("Diretório inconsistente na entrada " + i + ": " + line + ". Atualize a lista.");
            String name = line.substring(5).trim();
            if (name.isEmpty()) throw new IOException("Arquivo sem nome no diretório.");
            result.add(new FileEntry(i, name));
        }
        return result;
    }
    static final class IntegrityException extends IOException {
        IntegrityException(String message, Throwable cause) { super(message, cause); }
    }
    /** Import-only recovery: a non-AA reply requests the current FX block again. */
    public Capture importFile(FileEntry expected, java.util.function.Consumer<String> progress) throws IOException {
        return download(expected, progress);
    }
    public Capture download(FileEntry expected) throws IOException { return download(expected, null); }
    private Capture download(FileEntry expected, java.util.function.Consumer<String> progress) throws IOException {
        return download(expected, progress, false);
    }
    private Capture download(FileEntry expected, java.util.function.Consumer<String> progress, boolean catalogOnly) throws IOException {
        // Indexes are transient. Check the count here and the checksum-protected
        // FLNM in the downloaded header before saving anything. DR has no checksum.
        identify();
        int count = number(command("DL"), 0, 50000);
        if (expected.number < 1 || expected.number > count)
            throw new IOException("A lista do instrumento mudou. Conecte e liste novamente.");
        link.write("\u001bFX " + expected.number + "\r");
        Map<String, String> metadata = new LinkedHashMap<>();
        List<Reading> readings = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        String[] markers = {"[INSS] 000", "[INSE] 000", "[HDRS] 000", "[HDRE] 000",
            "[STCS] 000", "[STCE] 000", "[D2TS]", "[D2TE]"};
        int marker = 0;
        boolean data = false, ended = false;
        int recoveries = 0;
        for (int frame = 0; frame < 50500; frame++) {
            byte[] wire;
            String body;
            int attempt = 0;
            while (true) {
                wire = link.readLine();
                try { body = checkedBody(wire); break; }
                catch (IOException e) {
                    String detail = "Bloco " + (frame + 1) + " (" + wire.length + " bytes): " + e.getMessage();
                    if (progress == null || attempt >= 2 || recoveries >= 8)
                        throw new IntegrityException(detail + " Não foi possível recuperar a leitura; nenhuma captura parcial salva.", e);
                    attempt++; recoveries++;
                    progress.accept("Recuperando arquivo " + expected.number() + " · bloco " + (frame + 1)
                        + " · releitura " + attempt + " de 2. " + detail);
                    // Firmware FX repeats the same block for a non-AA response.
                    // Never acknowledge corrupt bytes or start another command inside FX.
                    link.discardInput();
                    link.write("NN\n\r");
                }
            }
            raw.add(Base64.getEncoder().encodeToString(wire));
            if (body.startsWith("[")) {
                if (marker >= markers.length || !markers[marker].equals(body))
                    throw new IOException("Sequência de blocos desconhecida: " + body);
                marker++;
                data = marker == 7;
                ended = marker == 8;
            } else if (data && metadata.containsKey("RCFM")) {
                // Catalog scan validates framing/count without interpreting unsupported measurement formats.
                readings.add(catalogOnly ? new Reading("", "", "", body) : reading(body, metadata, readings.size()));
                if (readings.size() > number(required(metadata, "NMBR"), catalogOnly ? 0 : 1, 50000))
                    throw new IOException("O instrumento enviou pontos além da contagem declarada.");
            } else {
                if (!(marker == 1 || marker == 3 || marker == 5 || marker == 7))
                    throw new IOException("Metadado fora de bloco.");
                if (body.length() < 9 || body.charAt(4) != ' ' || body.charAt(8) != ' ')
                    throw new IOException("Cabeçalho de arquivo inválido.");
                String key = body.substring(0, 4);
                int length = number(body.substring(5, 8), 0, 4096);
                if (body.length() != 9 + length || metadata.containsKey(key))
                    throw new IOException("Tamanho ou duplicação de metadado inválido: " + key);
                metadata.put(key, clean(body.substring(9)));
            }
            // Acknowledge only a checksum-verified and accepted frame.
            link.write("AA\n\r");
            if (ended) break;
        }
        if (!ended || readings.size() != number(required(metadata, "NMBR"), catalogOnly ? 0 : 1, 50000))
            throw new IOException("Transferência incompleta; nenhum dado salvo.");
        if (!catalogOnly && !expected.name.equals(required(metadata, "FLNM")))
            throw new IOException("Nome recebido diferente do arquivo selecionado.");
        if (!catalogOnly) validateLayout(metadata);
        identify();
        return new Capture(expected, Collections.unmodifiableMap(metadata), List.copyOf(readings), List.copyOf(raw));
    }
    static String checkedBody(byte[] line) throws IOException {
        if (line.length < 5 || line.length > 8192) throw new IOException("Bloco inválido ou excessivo.");
        int size = line.length - 4;
        String tail = new String(line, size, 4, StandardCharsets.US_ASCII).trim();
        if (!tail.matches("[0-9a-fA-F]{1,4}")) throw new IOException("Checksum ausente.");
        int sum = 0;
        for (int i = 0; i < size; i++) sum = (sum + (line[i] & 255)) & 65535;
        if (sum != Integer.parseInt(tail, 16)) throw new IOException("Checksum incorreto; captura descartada.");
        return new String(line, 0, size, StandardCharsets.ISO_8859_1);
    }
    static Reading reading(String body, Map<String, String> meta, int index) throws IOException {
        validateLayout(meta);
        if (!"2.1".equals(required(meta, "VERS")) || !"3 3 7 7 1 4 14".equals(required(meta, "RCFM")))
            throw new IOException("Versão ou formato de registro ainda não suportado.");
        if (body.length() != 39) throw new IOException("Registro com tamanho incorreto.");
        int columns = number(required(meta, "L3NL"), 1, 50000);
        int rowStart = number(required(meta, "L2SI"), 0, 50000);
        int columnStart = number(required(meta, "L3SI"), 1, 50000);
        String row = clean(body.substring(0, 3));
        String col = clean(body.substring(3, 6));
        if (!row.equals(Integer.toString(rowStart + index / columns)) || !col.equals(alpha(columnStart + index % columns)))
            throw new IOException("Posição fora de ordem ou incompatível com a matriz.");
        String value = clean(body.substring(6, 13));
        // Empty cells validated by a physical FU / FX round trip. No zero is invented.
        if (body.substring(6).equals("\u00ff".repeat(33)) && "MM".equals(required(meta, "UNIT")))
            return new Reading(row + col, "", "mm", body);
        // Other missing/obstruction encodings remain rejected.
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?"))
            throw new IOException("Estado de leitura ainda não suportado na posição " + row + col + ": " + value);
        if (new BigDecimal(value).compareTo(new BigDecimal("1000000")) > 0)
            throw new IOException("Valor fora do limite de validação.");
        String unitCode = body.substring(20, 21);
        if (!"M".equals(unitCode) || !"MM".equals(required(meta, "UNIT")))
            throw new IOException("Unidade ainda não validada nesta versão. Captura não salva.");
        return new Reading(row + col, value, "mm", body);
    }
    static void validateLayout(Map<String, String> meta) throws IOException {
        if (!"2".equals(required(meta, "TPNB")) || !"LR".equals(required(meta, "ADDR")))
            throw new IOException("Esta versão suporta a matriz Grid LR observada no DM5E.");
        long rows = number(required(meta, "L2NL"), 1, 50000);
        long cols = number(required(meta, "L3NL"), 1, 50000);
        if (rows * cols != number(required(meta, "NMBR"), 1, 50000))
            throw new IOException("Dimensões e quantidade de pontos não coincidem.");
    }
    static String required(Map<String, String> meta, String key) throws IOException {
        String value = meta.get(key);
        if (value == null) throw new IOException("Campo obrigatório ausente: " + key);
        return value;
    }
    static int number(String value, int min, int max) throws IOException {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int n = Integer.parseInt(value);
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new IOException("Número inválido no protocolo: " + value); }
    }
    static String clean(String value) { return value.replace('\u00ff', ' ').trim(); }
    static String alpha(int value) {
        StringBuilder out = new StringBuilder();
        while (value > 0) { value--; out.insert(0, (char) ('A' + value % 26)); value /= 26; }
        return out.toString();
    }
}
