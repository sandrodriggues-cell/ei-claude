package com.eiclaude.snoop;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pequena página de ajustes servida pelo próprio relógio, na rede Wi-Fi: assim a chave do Gemini
 * é colada no navegador do celular, em vez de digitada no relógio. Só funciona com o endereço
 * completo (com o código de 4 dígitos) e só enquanto a tela de ajustes estiver aberta.
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
        Thread t = new Thread(this::loop, "Snoop-ajustes");
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
            Thread worker = new Thread(() -> {
                try (Socket sock = s) {
                    sock.setSoTimeout(10000);
                    handle(sock);
                } catch (Throwable ignored) {
                    // conexão encerrada pelo navegador
                }
            }, "Snoop-ajustes-pedido");
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
        if (contentLength > 0 && contentLength <= 16384) body = readBytes(in, contentLength);

        OutputStream out = s.getOutputStream();
        if (path.equals("/favicon.ico")) {
            respond(out, 404, "text/plain", "");
            return;
        }
        if (!path.equals("/" + pin)) {
            respond(out, 403, "text/html", page("<div class=\"card warn\"><p>Abra o endereço completo que aparece "
                    + "no relógio, com o código de 4 dígitos no final.</p></div>"));
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
        if (!key.isEmpty()) {
            if (!Prefs.looksLikeKey(key)) {
                return form("<div class=\"card warn\"><p><b>Essa chave não parece certa.</b> Copie de novo a chave "
                        + "inteira no AI Studio (sem espaços) e cole aqui.</p></div>");
            }
            Prefs.setApiKey(context, key);
        }
        Prefs.setName(context, trim(form.get("nome")));
        String model = trim(form.get("modelo"));
        if (model.equals(Prefs.MODEL_FAST) || model.equals(Prefs.MODEL_SMART)) Prefs.setModel(context, model);
        Prefs.setVoice(context, trim(form.get("voz")));
        String gv = trim(form.get("voz_gemini"));
        for (String v : Prefs.GEMINI_VOICES) if (v.equals(gv)) Prefs.setGeminiVoice(context, v);
        try {
            Prefs.setIdleMinutes(context, Integer.parseInt(trim(form.get("parado"))));
        } catch (NumberFormatException ignored) {
            // mantém
        }
        if (form.containsKey("saudacao_liga")) {
            int m = minutes(form.get("saudacao"));
            if (m >= 0) Prefs.setGreetMinute(context, m);
        } else {
            Prefs.setGreetMinute(context, -1);
        }
        if (form.containsKey("silencio_liga")) {
            int a = minutes(form.get("silencio_ini"));
            int b = minutes(form.get("silencio_fim"));
            if (a >= 0 && b >= 0) Prefs.setQuiet(context, a, b);
        } else {
            Prefs.setQuiet(context, 0, 0);
        }
        Prefs.setMissYou(context, form.containsKey("saudade"));
        Pulse.schedule(context);

        if (!Prefs.hasKey(context)) {
            return form("<div class=\"card warn\"><p>Ajustes salvos, mas ainda falta colar a chave do Gemini.</p></div>");
        }
        boolean ok;
        String message;
        try {
            Gemini.chat(Prefs.apiKey(context), Prefs.model(context), "Você é um teste. Responda só: oi.",
                    new ArrayList<>(), null, null, "teste");
            ok = true;
            message = "Tudo certo! O Snoop já consegue conversar com o Gemini. Pode voltar para o relógio.";
        } catch (Gemini.ApiException e) {
            ok = false;
            message = "Salvei, mas o teste com o Gemini falhou: " + e.getMessage();
        }
        final boolean fOk = ok;
        final String fMessage = message;
        main.post(() -> listener.onSaved(fOk, fMessage));
        String card = ok
                ? "<div class=\"card ok\"><p><b>" + Text.html(message) + "</b></p></div>"
                : "<div class=\"card warn\"><p><b>" + Text.html(message) + "</b></p></div>";
        return form(card);
    }

    private static int minutes(String hhmm) {
        if (hhmm == null) return -1;
        String[] p = hhmm.trim().split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ------------------------------------------------------------------ páginas

    private String form(String notice) {
        boolean hasKey = Prefs.hasKey(context);
        StringBuilder b = new StringBuilder();
        if (notice != null) b.append(notice);
        b.append("<form method=\"post\" action=\"/").append(pin).append("\">");

        b.append("<div class=\"card\"><label for=\"chave\">Chave do Gemini</label>");
        b.append("<input id=\"chave\" name=\"chave\" type=\"password\" autocomplete=\"off\" autocapitalize=\"off\" "
                + "autocorrect=\"off\" spellcheck=\"false\" placeholder=\"")
                .append(hasKey ? "já configurada; deixe vazio para manter" : "cole a chave aqui").append("\">");
        b.append("<label class=\"inline\"><input type=\"checkbox\" onclick=\"var c=document.getElementById('chave');"
                + "c.type=this.checked?'text':'password'\"> mostrar</label>");
        b.append("<p class=\"hint\">Crie de graça em aistudio.google.com/apikey e copie a chave inteira.</p></div>");

        b.append("<div class=\"card\"><label for=\"nome\">Como o Snoop chama você</label>");
        b.append("<input id=\"nome\" name=\"nome\" type=\"text\" maxlength=\"40\" value=\"")
                .append(Text.html(Prefs.name(context))).append("\"></div>");

        String model = Prefs.model(context);
        b.append("<div class=\"card\"><span class=\"label\">Cérebro</span>");
        b.append(radio("modelo", Prefs.MODEL_FAST, "Rápido (Gemini 3.5 Flash-Lite)",
                "Responde mais depressa. Recomendado para conversar por voz.", model));
        b.append(radio("modelo", Prefs.MODEL_SMART, "Esperto (Gemini 3.8 Flash)",
                "Pensa um pouco mais antes de responder; demora mais.", model));
        b.append("</div>");

        String voice = Prefs.voice(context);
        b.append("<div class=\"card\"><span class=\"label\">Voz do Snoop</span>");
        b.append(radio("voz", Prefs.VOICE_WATCH, "Leitor do relógio",
                "Fala na hora e não gasta o plano grátis.", voice));
        b.append(radio("voz", Prefs.VOICE_GEMINI, "Voz do Gemini",
                "Mais natural e expressiva; leva uns segundos a mais e usa o plano grátis.", voice));
        b.append("<label for=\"voz_gemini\" class=\"sub2\">Voz do Gemini</label><select id=\"voz_gemini\" name=\"voz_gemini\">");
        String gv = Prefs.geminiVoice(context);
        for (String v : Prefs.GEMINI_VOICES) {
            b.append("<option").append(v.equals(gv) ? " selected" : "").append(">").append(v).append("</option>");
        }
        b.append("</select></div>");

        int idle = Prefs.idleMinutes(context);
        b.append("<div class=\"card\"><label for=\"parado\">Avisar quando eu ficar parado por</label><select id=\"parado\" name=\"parado\">");
        for (int m : new int[]{45, 60, 90, 120, 0}) {
            b.append("<option value=\"").append(m).append("\"").append(m == idle ? " selected" : "").append(">")
                    .append(m == 0 ? "não avisar" : m + " minutos").append("</option>");
        }
        b.append("</select><p class=\"hint\">Usa os passos do relógio. Não avisa com o relógio carregando.</p></div>");

        int gm = Prefs.greetMinute(context);
        b.append("<div class=\"card\"><label class=\"inline\"><input type=\"checkbox\" name=\"saudacao_liga\"")
                .append(gm >= 0 ? " checked" : "").append("> Saudação todo dia às</label>");
        b.append("<input type=\"time\" name=\"saudacao\" value=\"").append(Prefs.hhmm(gm >= 0 ? gm : 14 * 60)).append("\">");
        b.append("<p class=\"hint\">Use o horário em que você costuma acordar.</p></div>");

        int qs = Prefs.quietStart(context);
        int qe = Prefs.quietEnd(context);
        boolean quietOn = qs != qe;
        b.append("<div class=\"card\"><label class=\"inline\"><input type=\"checkbox\" name=\"silencio_liga\"")
                .append(quietOn ? " checked" : "").append("> Silêncio (sem avisos) das</label>");
        b.append("<div class=\"row\"><input type=\"time\" name=\"silencio_ini\" value=\"")
                .append(Prefs.hhmm(quietOn ? qs : 8 * 60)).append("\"><span>às</span><input type=\"time\" name=\"silencio_fim\" value=\"")
                .append(Prefs.hhmm(quietOn ? qe : 14 * 60)).append("\"></div>");
        b.append("<p class=\"hint\">O modo Não perturbe e o modo dormir do relógio também calam os avisos.</p></div>");

        b.append("<div class=\"card\"><label class=\"inline\"><input type=\"checkbox\" name=\"saudade\"")
                .append(Prefs.missYou(context) ? " checked" : "")
                .append("> Avisar quando ficar 2 dias sem conversar (saudade)</label></div>");

        b.append("<button type=\"submit\">Salvar e testar</button></form>");
        b.append("<p class=\"foot\">Esta página vem do próprio relógio, pela sua rede Wi-Fi, e para de funcionar "
                + "quando você sai dos ajustes no relógio. A chave fica guardada só no relógio.</p>");
        return page(b.toString());
    }

    private static String radio(String name, String value, String title, String detail, String current) {
        return "<label class=\"opt\"><input type=\"radio\" name=\"" + name + "\" value=\"" + value + "\""
                + (value.equals(current) ? " checked" : "") + "><span><b>" + title + "</b><br>"
                + "<span class=\"hint\">" + detail + "</span></span></label>";
    }

    private static String page(String content) {
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Snoop · ajustes</title><style>"
                + ":root{--bg:#F7F0E6;--card:#fff;--ink:#2A1C14;--muted:#7A6A5C;--accent:#C8742F;--line:#EADCCB}"
                + "*{box-sizing:border-box}"
                + "body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,Roboto,sans-serif}"
                + "main{max-width:520px;margin:0 auto;padding:22px 16px 40px}"
                + "h1{font-size:22px;margin:0 0 4px}.sub{color:var(--muted);margin:0 0 6px}"
                + ".card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin-top:14px}"
                + ".card.ok{border-color:#15803D;background:#F0FDF4}.card.warn{border-color:#B45309;background:#FFFBEB}"
                + "label,.label{display:block;font-weight:600;margin-bottom:8px}"
                + "label.inline{display:flex;gap:8px;align-items:center;margin:0 0 8px}"
                + "label.sub2{margin-top:12px}"
                + "input[type=text],input[type=password],input[type=time],select{width:100%;padding:12px;"
                + "border:1px solid var(--line);border-radius:10px;font:15px system-ui,Roboto,sans-serif;background:#fff;color:var(--ink)}"
                + "input[type=password],#chave{font-family:ui-monospace,Menlo,monospace}"
                + ".row{display:flex;gap:10px;align-items:center}.row span{color:var(--muted)}"
                + ".opt{display:flex;gap:10px;align-items:flex-start;font-weight:400;padding:8px 0;margin:0}"
                + ".opt input{margin-top:4px}.hint{color:var(--muted);font-size:14px;margin:8px 0 0;font-weight:400}"
                + "button{width:100%;margin-top:18px;padding:15px;border:0;border-radius:12px;"
                + "background:var(--accent);color:#fff;font-size:16px;font-weight:600}"
                + ".foot{color:var(--muted);font-size:13px;margin-top:18px}"
                + "</style></head><body><main><h1>Snoop</h1>"
                + "<p class=\"sub\">Ajustes do seu cachorro virtual no Galaxy Watch.</p>"
                + content + "</main></body></html>";
    }
}
