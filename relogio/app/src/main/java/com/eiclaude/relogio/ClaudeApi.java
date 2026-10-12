package com.eiclaude.relogio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Chamada direta à API de Mensagens do Claude (POST /v1/messages), sem bibliotecas.
 * Pensada para conversa por voz: pede respostas rápidas (sem raciocínio estendido, esforço baixo).
 */
final class ClaudeApi {

    private static final String URL_MESSAGES = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    static final class Msg {
        final String role;
        final String text;

        Msg(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    static final class Reply {
        String text = "";
        String stopReason = "";
        long inputTokens;
        long outputTokens;
    }

    /** Erro já traduzido para uma frase curta em português, boa para ser lida em voz alta. */
    static final class ApiException extends Exception {
        final int status;
        final String detail;

        ApiException(String message, int status, String detail) {
            super(message);
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }
    }

    private ClaudeApi() {
    }

    static Reply send(String apiKey, String model, String system, List<Msg> history, int maxTokens)
            throws ApiException {
        boolean fast = true;
        ApiException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return post(apiKey, body(model, system, history, maxTokens, fast));
            } catch (ApiException e) {
                last = e;
                String d = e.detail.toLowerCase(Locale.ROOT);
                if (fast && e.status == 400
                        && (d.contains("thinking") || d.contains("effort") || d.contains("output_config"))) {
                    fast = false; // modelo não aceita esses ajustes: tenta a forma padrão
                    continue;
                }
                boolean busy = e.status == 500 || e.status == 503 || e.status == 504 || e.status == 529;
                if (busy && attempt == 0) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    private static JSONObject body(String model, String system, List<Msg> history, int maxTokens, boolean fast)
            throws ApiException {
        try {
            JSONObject b = new JSONObject();
            b.put("model", model);
            b.put("max_tokens", maxTokens);
            if (system != null && !system.isEmpty()) b.put("system", system);
            JSONArray messages = new JSONArray();
            for (Msg m : history) {
                messages.put(new JSONObject().put("role", m.role).put("content", m.text));
            }
            b.put("messages", messages);
            if (fast) {
                b.put("thinking", new JSONObject().put("type", "disabled"));
                b.put("output_config", new JSONObject().put("effort", "low"));
            }
            return b;
        } catch (JSONException e) {
            throw new ApiException("Não consegui montar o pedido para o Claude.", -1, e.getMessage());
        }
    }

    private static Reply post(String apiKey, JSONObject body) throws ApiException {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(URL_MESSAGES).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("x-api-key", apiKey);
            con.setRequestProperty("anthropic-version", API_VERSION);
            con.setRequestProperty("content-type", "application/json");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = con.getOutputStream()) {
                os.write(out);
            }
            int code = con.getResponseCode();
            String text = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
            if (code != 200) throw error(code, text);
            return parse(text);
        } catch (ApiException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new ApiException("Estou sem internet. Confira a conexão do relógio com o celular ou o Wi-Fi.",
                    -1, "sem DNS");
        } catch (SocketTimeoutException e) {
            throw new ApiException("O Claude demorou demais para responder. Tente de novo.", -1, "timeout");
        } catch (IOException e) {
            throw new ApiException("Não consegui falar com o Claude. Confira a internet do relógio.", -1,
                    String.valueOf(e.getMessage()));
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static Reply parse(String text) throws ApiException {
        try {
            JSONObject j = new JSONObject(text);
            Reply r = new Reply();
            StringBuilder sb = new StringBuilder();
            JSONArray content = j.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block == null || !"text".equals(block.optString("type"))) continue;
                    String t = block.optString("text", "").trim();
                    if (t.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t);
                }
            }
            r.text = sb.toString();
            r.stopReason = j.optString("stop_reason", "");
            JSONObject usage = j.optJSONObject("usage");
            if (usage != null) {
                r.inputTokens = usage.optLong("input_tokens", 0);
                r.outputTokens = usage.optLong("output_tokens", 0);
            }
            return r;
        } catch (JSONException e) {
            throw new ApiException("A resposta do Claude veio num formato inesperado.", 200, e.getMessage());
        }
    }

    private static ApiException error(int code, String body) {
        String type = "";
        String message = "";
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            if (e != null) {
                type = e.optString("type", "");
                message = e.optString("message", "");
            }
        } catch (JSONException ignored) {
            // corpo sem JSON
        }
        String lower = (type + " " + message).toLowerCase(Locale.ROOT);
        String pt;
        if (code == 401) {
            pt = "A chave da API foi recusada. Configure a chave de novo nos ajustes.";
        } else if (code == 402 || lower.contains("credit") || lower.contains("billing")) {
            pt = "A conta da API está sem créditos. Adicione créditos no Console do Claude.";
        } else if (code == 403) {
            pt = "Essa chave não tem permissão para usar o Claude.";
        } else if (code == 404) {
            pt = "Não achei o modelo escolhido. Troque o modelo nos ajustes.";
        } else if (code == 429) {
            pt = "Atingi o limite de uso da API. Tente de novo daqui a pouco.";
        } else if (code == 500 || code == 503 || code == 504 || code == 529) {
            pt = "O Claude está sobrecarregado agora. Tente de novo em instantes.";
        } else if (lower.contains("spend")) {
            pt = "A API recusou o pedido por limite de gastos. Confira os limites no Console.";
        } else {
            pt = "A API respondeu com erro " + code + ".";
        }
        return new ApiException(pt, code, type + ": " + message);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
