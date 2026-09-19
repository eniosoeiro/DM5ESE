package com.dm5ese.usbprobe;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** MS: thickness in micrometres, timing field, C/U coupling indicator. */
public record Dm5eLiveReading(int micrometres, int timing, boolean coupled, String raw) {
    public BigDecimal millimetres() { return BigDecimal.valueOf(micrometres, 3); }
    public static Dm5eLiveReading parse(byte[] bytes) throws IOException {
        String raw = new String(bytes, StandardCharsets.US_ASCII);
        if (!raw.matches("[0-9]{1,10},[0-9]{1,10},[CU]"))
            throw new IOException("Resposta numérica MS inválida.");
        String[] fields = raw.split(",");
        try {
            int thickness = Integer.parseInt(fields[0]), timing = Integer.parseInt(fields[1]);
            if (thickness <= 0 && fields[2].equals("C")) throw new NumberFormatException();
            return new Dm5eLiveReading(thickness, timing, fields[2].equals("C"), raw);
        } catch (NumberFormatException e) { throw new IOException("Valor MS fora do intervalo válido.", e); }
    }
}
