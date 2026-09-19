package com.dm5ese.usbprobe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Bounded app diagnostics. No system-wide logcat or unrelated files. */
final class DiagnosticLog {
    private final File folder, sessions;
    DiagnosticLog(File files) throws IOException {
        folder = new File(files, "support-logs"); sessions = new File(files, "live-diagnostics");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Falha ao criar pasta de logs.");
    }
    synchronized void event(String type, String detail) {
        try {
            File current = new File(folder, "events.txt"), previous = new File(folder, "previous.txt");
            if (current.length() > 262144) {
                Files.move(current.toPath(), previous.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (Writer out = new OutputStreamWriter(new FileOutputStream(current, true), StandardCharsets.UTF_8)) {
                out.write(Instant.now() + " | " + type + " | " + detail + "\n");
            }
        } catch (IOException e) { android.util.Log.e("DM5ESE", "Falha ao registrar log", e); }
    }
    synchronized byte[] archive(String report) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("diagnostico.txt"));
            zip.write(("Gerado em " + Instant.now() + "\n" + report
                + "\n\nContém eventos deste app e até 3 sessões recentes (podem incluir medições e imagens do visor)."
                + "\nNão contém o log geral do Android. Arquivos de sessão podem estar em andamento.\n").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (String name : new String[]{"previous.txt", "events.txt"}) add(zip, new File(folder, name), "eventos/" + name);
            File[] logs = sessions.listFiles((dir, name) -> name.endsWith(".jsonl"));
            if (logs != null) {
                Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());
                for (int i = 0; i < Math.min(3, logs.length); i++) add(zip, logs[i], "sessoes/" + logs[i].getName());
            }
        }
        return bytes.toByteArray();
    }
    private void add(ZipOutputStream zip, File file, String name) throws IOException {
        if (!file.isFile()) return;
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[8192]; int remaining = 1048576, count;
            while (remaining > 0 && (count = in.read(data, 0, Math.min(data.length, remaining))) != -1) {
                zip.write(data, 0, count); remaining -= count;
            }
        }
        zip.closeEntry();
    }
}
