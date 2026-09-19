package com.dm5ese.usbprobe;

import android.hardware.usb.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** CDC ACM transport, 115200 8N1, DTR/RTS asserted as observed in the GE session. */
final class Dm5eUsb implements Dm5eProtocol.Link, AutoCloseable {
    private final UsbDeviceConnection connection;
    private final UsbInterface control, data;
    private final UsbEndpoint in, out;
    private final AtomicBoolean cancelled;
    private final long deadline;
    private final byte[] buffer = new byte[16384];
    private final BlockingQueue<byte[]> received = new ArrayBlockingQueue<>(2048);
    private final List<UsbRequest> requests = new ArrayList<>();
    private volatile boolean closed;
    private volatile IOException receiveError;
    private Thread reader;
    private int offset, available;
    Dm5eUsb(UsbManager usb, UsbDevice device, AtomicBoolean cancelled) throws IOException {
        this(usb,device,cancelled,180000);
    }
    Dm5eUsb(UsbManager usb, UsbDevice device, AtomicBoolean cancelled, long sessionMillis) throws IOException {
        deadline=SystemClock.elapsedRealtime()+Math.min(900000,Math.max(180000,sessionMillis));
        this.cancelled = cancelled;
        if (device.getVendorId() != 0xc251 || device.getProductId() != 0x1705)
            throw new IOException("Dispositivo não é o DM5E esperado.");
        UsbInterface ctrl = null, dat = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 2 && iface.getInterfaceSubclass() == 2) ctrl = iface;
            if (iface.getInterfaceClass() == 10) dat = iface;
        }
        if (ctrl == null || dat == null) throw new IOException("Interfaces CDC ACM ausentes.");
        control = ctrl; data = dat;
        UsbEndpoint input = null, output = null;
        for (int i = 0; i < data.getEndpointCount(); i++) {
            UsbEndpoint ep = data.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) input = ep; else output = ep;
        }
        if (input == null || output == null) throw new IOException("Endpoints de dados ausentes.");
        in = input; out = output;
        connection = usb.openDevice(device);
        if (connection == null) throw new IOException("Sem acesso USB. Autorize e reconecte.");
        try {
            check();
            if (!connection.claimInterface(control, true) || !connection.claimInterface(data, true))
                throw new IOException("Não foi possível reservar as interfaces USB.");
            byte[] coding = {0, (byte) 0xc2, 1, 0, 0, 0, 8};
            if (connection.controlTransfer(0x21, 0x20, 0, control.getId(), coding, 7, 2000) != 7
                || connection.controlTransfer(0x21, 0x22, 3, control.getId(), null, 0, 2000) < 0)
                throw new IOException("Falha ao configurar a comunicação serial USB.");
            // Keep multiple reads pending: this old CDC firmware emits short packets
            // continuously during DR, and a synchronous read/process/read loop loses bytes.
            for (int i = 0; i < 32; i++) {
                UsbRequest request = new UsbRequest();
                if (!request.initialize(connection, in)) throw new IOException("Falha ao preparar leitura USB.");
                ByteBuffer bytes = ByteBuffer.allocateDirect(16384);
                request.setClientData(bytes); requests.add(request);
                if (!request.queue(bytes)) throw new IOException("Falha ao enfileirar leitura USB.");
            }
            reader = new Thread(this::receive, "dm5e-usb-reader"); reader.setDaemon(true); reader.start();
            SystemClock.sleep(100); received.clear();
        } catch (Exception e) { close(); throw e; }
    }
    private void check() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Captura cancelada.");
        if (receiveError != null) throw receiveError;
        if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Tempo limite da sessão USB atingido.");
    }
    @Override public void write(String command) throws IOException {
        check();
        SystemClock.sleep(command.startsWith("\u001b") ? 80 : 20);
        byte[] bytes = command.getBytes(StandardCharsets.ISO_8859_1);
        int sent = connection.bulkTransfer(out, bytes, bytes.length, 2000);
        if (sent != bytes.length) throw new IOException("Falha no envio USB; reconecte o DM5E.");
    }
    @Override public void readAck() throws IOException {
        if (readLine(true).length != 0) throw new IOException("Envio recusado pelo DM5E.");
    }
    @Override public byte[] readLine() throws IOException { return readLine(false); }
    private byte[] readLine(boolean acknowledgement) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long until = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < until) {
            check();
            if (offset == available) {
                try {
                    byte[] chunk = received.poll(250, TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;
                    System.arraycopy(chunk, 0, buffer, 0, chunk.length); available = chunk.length; offset = 0;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Leitura interrompida.", e); }
            }
            int value = buffer[offset++] & 255;
            if (line.size() == 0 && (value == 10 || (value == 13 && !acknowledgement))) continue;
            if (value == 13) return line.toByteArray();
            if (line.size() >= 8192) throw new IOException("Resposta USB excessiva.");
            line.write(value);
        }
        throw new IOException("DM5E não respondeu em 5 segundos. Confira o cabo e tente novamente.");
    }
    @Override public void discardInput() throws IOException {
        offset = available = 0;
        long until = SystemClock.elapsedRealtime() + 3000;
        long quietSince = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() < until) {
            check();
            // A fixed delay can end while the previous response is still arriving.
            // Require silence before sending another command; never splice replies.
            if (received.poll() != null) quietSince = SystemClock.elapsedRealtime();
            else if (SystemClock.elapsedRealtime() - quietSince >= 500) return;
            else SystemClock.sleep(10);
        }
        throw new IOException("O fluxo USB não estabilizou. Reconecte o cabo e liste novamente.");
    }
    @Override public void awaitDeletion() throws IOException {
        // DF has no required ACK. Completion is established by the following directory read.
        long until = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < until) { check(); SystemClock.sleep(25); }
        discardInput();
    }
    /** Read-only screen request, isolated from line-oriented file transfers. */
    byte[] readScreen() throws IOException {
        discardInput();
        write("\u001b8Y\r");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        long until = SystemClock.elapsedRealtime() + 2500;
        while (frame.size() < Dm5eScreen.BYTES && SystemClock.elapsedRealtime() < until) {
            byte[] chunk = pollInput();
            if (chunk != null) frame.write(chunk);
        }
        // Return incomplete frames to the caller for counting, never pad or display them.
        return frame.toByteArray();
    }
    /** Raw bytes, including NUL/CR/LF: screen pixels are not ASCII lines. */
    byte[] pollInput() throws IOException {
        check();
        if (offset < available) {
            byte[] pending = Arrays.copyOfRange(buffer, offset, available); offset = available; return pending;
        }
        try { return received.poll(250, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Escuta interrompida.", e); }
    }
    private void receive() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            while (!closed && !cancelled.get()) {
                UsbRequest request;
                try { request = connection.requestWait(250); } catch (TimeoutException e) { continue; }
                if (request == null) throw new IOException("Conexão USB interrompida.");
                ByteBuffer bytes = (ByteBuffer) request.getClientData();
                int count = bytes.position(); byte[] chunk = new byte[count]; bytes.flip(); bytes.get(chunk); bytes.clear();
                if (!closed && !request.queue(bytes)) throw new IOException("Fila USB interrompida.");
                if (count > 0 && !received.offer(chunk)) throw new IOException("Fluxo USB excedeu o buffer; captura descartada.");
            }
        } catch (Exception e) { if (!closed) receiveError = new IOException("Recepção USB: " + e.getMessage(), e); }
    }
    @Override public void close() {
        closed = true;
        connection.controlTransfer(0x21, 0x22, 0, control.getId(), null, 0, 1000);
        for (UsbRequest request : requests) request.cancel();
        if (reader != null) {
            try { reader.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        for (UsbRequest request : requests) request.close();
        connection.releaseInterface(data);
        connection.releaseInterface(control);
        connection.close();
    }
}
