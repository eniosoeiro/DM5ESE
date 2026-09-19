package com.dm5ese.usbprobe;

import java.io.IOException;

/** Bounded, foreground-only retry policy. Never expose server bodies or tokens. */
final class ThicknessSyncRules {
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_BATCH = 10;
    static final int MAX_BYTES = 3 * 1024 * 1024;
    static final String PRODUCTION = "https://kmapwhnaeckgtsqnqvrg.supabase.co";
    static final class Failure extends IOException {
        final boolean retryable;
        Failure(String message, boolean retryable) { super(message); this.retryable = retryable; }
    }
    static long delay(int attempts) { return Math.min(300_000L, 2_000L << Math.min(8, Math.max(0, attempts - 1))); }
    static boolean due(String state, int attempts, long next, long now) {
        return ("PENDING".equals(state) || "SENDING".equals(state)) && attempts < MAX_ATTEMPTS && now >= next;
    }
    static Failure httpFailure(int code) {
        if (code == 401 || code == 403) return new Failure("Sessão expirada ou conta sem acesso. Entre novamente; os arquivos foram preservados.", false);
        if (code == 409) return new Failure("Conflito de revisão. Confira o arquivo antes de tentar novamente.", false);
        if (code == 429 || code == 408 || code >= 500) return new Failure("Servidor temporariamente indisponível. A revisão permanece pendente para nova tentativa.", true);
        return new Failure("O servidor recusou o envio (HTTP " + code + "). Confira a configuração e o formato da captura.", false);
    }
    static boolean retryable(Exception error) { return error instanceof Failure ? ((Failure) error).retryable : error instanceof IOException; }
    static String safeMessage(Exception error) {
        return error instanceof Failure ? error.getMessage() : error instanceof IOException
            ? "Falha de conexão ou armazenamento. Seus arquivos permanecem no celular."
            : "Dados ou resposta incompatíveis. A captura não foi marcada como recebida.";
    }
    private ThicknessSyncRules() {}
}
