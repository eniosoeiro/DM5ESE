package com.dm5ese.usbprobe;

/** A preview is never a saved value. All timestamps here are monotonic. */
final class CellMeasurement {
    String position;
    Dm5eLiveReading reading;
    long receivedAt;
    void select(String position) { this.position = position; clear(); }
    void clear() { reading = null; receivedAt = 0; }
    void accept(Dm5eLiveReading reading, long now) { this.reading = reading; receivedAt = now; }
    boolean canSave(long now) {
        return position != null && reading != null && reading.coupled() && reading.micrometres() > 0
            && now >= receivedAt && now - receivedAt < 1500;
    }
    Dm5eLiveReading snapshot(long now) {
        if (!canSave(now)) throw new IllegalStateException("Aguarde uma leitura recente com o cabeçote acoplado.");
        return reading;
    }
}
