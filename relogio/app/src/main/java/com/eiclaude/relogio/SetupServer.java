package com.eiclaude.relogio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pequena página de ajustes servida pelo próprio relógio, na rede Wi-Fi.
 * Assim a chave da API é colada no navegador do celular, em vez de digitada no relógio.
 * Só funciona com o endereço completo (com o código de 4 dígitos mostrado no relógio) e só
 * enquanto a tela de ajustes estiver aberta.
 */
final class SetupServer {

    interface Listener {
        /** Chamado na thread principal depois de salvar pela página. */
        void onSaved(boolean keyWorks, String message);
    }

    static final int FIRST_PORT = 8765;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String pin;

    private volatile ServerSocket socket;
    private volatile boolean running;
    private int port;

    SetupServer(Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
        pin = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
    }

    String pin() {
        return pin;
    }

    int port() {
        return port;
    }

    boolean isRunning() {
        return running;
    }

    void start() throws IOException {
        if (running) return;
        IOException last = null;
        for (int p = FIRST_PORT; p < FIRST_PORT + 5; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                socket = ss;
                port = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (socket == null) throw last != null ? last : new IOException("porta ocupada");
        running = true;
        Thread t = new Thread(this::loop, "EiClaude-ajustes");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        ServerSocket ss = socket;
        socket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // já fechado
            }
        }
    }

    private void loop() {
        while (running) {
            ServerSocket ss = socket;
            if (ss == null) break;
            final Socket s;
            try {
                s = ss.accept();
            } catch (Throwable t) {
                if (!running) break;
                continue;
            }
            // Uma linha por conexão: o navegador às vezes abre conexões "de reserva" sem pedir nada.
            Thread worker = new Thread(() -> {
                try (Socket sock = s) {
                    sock.setSoTimeout(10000);
                    handle(sock);
                } catch (Throwable ignored) {
                    // conexão encerrada pelo navegador
                }
            }, "EiClaude-ajustes-pedido");
            worker.setDaemon(true);
            worker.start();
        }
    }

    // ------------------------------------------------------------------ HTTP mínimo

    private void handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) return;
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    contentLength = 0;
                }
            }
        }
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        byte[] body = new byte[0];
        if (contentLength > 0 && contentLength <= 16384) {
            body = readBytes(in, contentLength);
        }

        OutputStream out = s.getOutputStream();
        if (path.equals("/favicon.ico")) {
            respond(out, 404, "text/plain", "");
            return;
        }
        if (!path.equals("/" + pin)) {
            respond(out, 403, "text/html", page("Endereço incompleto",
                    "<div class=\"card warn\"><p>Abra o endereço completo que aparece no relógio, "
                            + "com o código de 4 dígitos no final.</p></div>"));
            return;
        }
        if ("POST".equals(method)) {
            respond(out, 200, "text/html", save(parseForm(new String(body, StandardCharsets.UTF_8))));
        } else {
            respond(out, 200, "text/html", form(null));
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (!any) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        if (off == n) return buf;
        byte[] cut = new byte[off];
        System.arraycopy(buf, 0, cut, 0, off);
        return cut;
    }

    private static Map<String, String> parseForm(String body) {
        if (body == null || body.isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                // par inválido
            }
        }
        return out;
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    // ------------------------------------------------------------------ salvar e testar

    private String save(Map<String, String> form) {
        String key = trim(form.get("chave")).replaceAll("\\s+", "");
        String name = trim(form.get("nome"));
        String model = trim(form.get("modelo"));

        if (!key.isEmpty()) {
            if (!Prefs.looksLikeKey(key)) {
                return form("<div class=\"card warn\"><p><b>Essa chave não parece certa.</b> "
                        + "A chave da API do Claude começa com <code>sk-ant-</code> e não tem espaços. "
                        + "Copie de novo no Console e cole aqui.</p></div>");
            }
            Prefs.setApiKey(context, key);
        }
        Prefs.setName(context, name);
        Prefs.setModel(context, model);

        if (!Prefs.hasKey(context)) {
            return form("<div class=\"card warn\"><p>Falta colar a chave da API.</p></div>");
        }

        boolean ok;
        String message;
        try {
            ClaudeApi.send(Prefs.apiKey(context), Prefs.model(context), "Responda apenas: ok",
                    Collections.singletonList(new ClaudeApi.Msg("user", "teste")), 16);
            ok = true;
            message = "Tudo certo! A chave funciona com o Claude " + Prefs.modelLabel(Prefs.model(context))
                    + ". Pode voltar para o relógio.";
        } catch (ClaudeApi.ApiException e) {
            ok = false;
            message = "Salvei os ajustes, mas o teste falhou: " + e.getMessage();
        }
        final boolean fOk = ok;
        final String fMessage = message;
        main.post(() -> listener.onSaved(fOk, fMessage));

        String card = ok
                ? "<div class=\"card ok\"><p><b>" + Text.html(message) + "</b></p>"
                + "<p class=\"hint\">No relógio, o Ei Claude vai dizer um oi e já começar a ouvir.</p></div>"
                : "<div class=\"card warn\"><p><b>" + Text.html(message) + "</b></p></div>";
        return form(card);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ------------------------------------------------------------------ páginas

    private String form(String notice) {
        boolean hasKey = Prefs.hasKey(context);
        String model = Prefs.model(context);
        StringBuilder b = new StringBuilder();
        if (notice != null) b.append(notice);
        b.append("<form method=\"post\" action=\"/").append(pin).append("\">");

        b.append("<div class=\"card\"><label for=\"chave\">Chave da API do Claude</label>");
        b.append("<input id=\"chave\" name=\"chave\" type=\"password\" autocomplete=\"off\" autocapitalize=\"off\" "
                + "autocorrect=\"off\" spellcheck=\"false\" placeholder=\"")
                .append(hasKey ? "já configurada; deixe vazio para manter" : "sk-ant-…").append("\">");
        b.append("<label class=\"inline\"><input type=\"checkbox\" onclick=\"var c=document.getElementById('chave');"
                + "c.type=this.checked?'text':'password'\"> mostrar</label>");
        b.append("<p class=\"hint\">Crie em platform.claude.com, em Settings › API keys. "
                + "Ela começa com <code>sk-ant-</code>.</p></div>");

        b.append("<div class=\"card\"><span class=\"label\">Modelo</span>");
        b.append(option(Prefs.MODEL_HAIKU, "Claude Haiku 5.5",
                "O mais rápido e o mais barato. Recomendado para conversar por voz.", model));
        b.append(option(Prefs.MODEL_SONNET, "Claude Sonnet 5.5",
                "Mais inteligente, um pouco mais lento e mais caro.", model));
        b.append("</div>");

        b.append("<div class=\"card\"><label for=\"nome\">Como o Claude chama você</label>");
        b.append("<input id=\"nome\" name=\"nome\" type=\"text\" maxlength=\"40\" value=\"")
                .append(Text.html(Prefs.name(context))).append("\"></div>");

        b.append("<button type=\"submit\">Salvar e testar</button></form>");
        b.append("<p class=\"foot\">Esta página vem do próprio relógio, pela sua rede Wi-Fi. "
                + "Ela para de funcionar quando você sai dos ajustes no relógio.</p>");
        return page("Ei Claude no relógio", b.toString());
    }

    private static String option(String value, String title, String detail, String current) {
        return "<label class=\"opt\"><input type=\"radio\" name=\"modelo\" value=\"" + value + "\""
                + (value.equals(current) ? " checked" : "") + "><span><b>" + title + "</b><br>"
                + "<span class=\"hint\">" + detail + "</span></span></label>";
    }

    private static String page(String title, String content) {
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + Text.html(title) + "</title><style>"
                + ":root{--bg:#F6F1EA;--card:#fff;--ink:#1E1A16;--muted:#6E645A;--accent:#C2410C;--line:#E6DCD0}"
                + "*{box-sizing:border-box}"
                + "body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,Roboto,sans-serif}"
                + "main{max-width:520px;margin:0 auto;padding:22px 16px 40px}"
                + "h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);margin:0 0 6px}"
                + ".card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin-top:14px}"
                + ".card.ok{border-color:#15803D;background:#F0FDF4}.card.warn{border-color:#B45309;background:#FFFBEB}"
                + "label,.label{display:block;font-weight:600;margin-bottom:8px}"
                + "label.inline{display:flex;gap:8px;align-items:center;font-weight:400;margin:10px 0 0}"
                + "input[type=text],input[type=password]{width:100%;padding:12px;border:1px solid var(--line);"
                + "border-radius:10px;font:15px ui-monospace,Menlo,monospace;background:#fff;color:var(--ink)}"
                + ".opt{display:flex;gap:10px;align-items:flex-start;font-weight:400;padding:8px 0;margin:0}"
                + ".opt input{margin-top:4px}.hint{color:var(--muted);font-size:14px;margin:8px 0 0}"
                + "code{background:#F3ECE3;padding:1px 5px;border-radius:5px}"
                + "button{width:100%;margin-top:18px;padding:15px;border:0;border-radius:12px;"
                + "background:var(--accent);color:#fff;font-size:16px;font-weight:600}"
                + ".foot{color:var(--muted);font-size:13px;margin-top:18px}"
                + "</style></head><body><main><h1>Ei Claude no relógio</h1>"
                + "<p class=\"sub\">Ajustes do app do seu Galaxy Watch.</p>"
                + content + "</main></body></html>";
    }
}
