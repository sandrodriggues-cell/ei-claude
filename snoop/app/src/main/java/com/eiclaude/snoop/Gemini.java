package com.eiclaude.snoop;

import android.os.SystemClock;
import android.util.Base64;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conversa com a API do Gemini (plano grátis do Google AI Studio). */
final class Gemini {

    static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    static final String TTS_MODEL = "gemini-3.8-flash-tts";
    static final String TTS_MODEL_LITE = "gemini-3.8-flash-lite-tts";

    enum Kind { KEY, QUOTA, MODEL, NETWORK, BLOCKED, OTHER }

    static final class ApiException extends Exception {
        final Kind kind;
        final int code;

        ApiException(Kind kind, int code, String message) {
            super(message);
            this.kind = kind;
            this.code = code;
        }
    }

    static final class Reply {
        String heard = "";
        String say = "";
        String mood = "feliz";
        String remember = "";
        boolean end;
        String trick = "";
        String reminderWhen = "";
        String reminderText = "";
        boolean reminderDaily;
        String cancelReminder = "";
        String background = "";
        int tokensIn;
        int tokensOut;
    }

    static final class Audio {
        final byte[] pcm;
        final int rate;

        Audio(byte[] pcm, int rate) {
            this.pcm = pcm;
            this.rate = rate;
        }
    }

    private Gemini() {
    }

    // ------------------------------------------------------------------ conversa

    /**
     * Manda a conversa e a fala nova (áudio ou texto) e devolve a resposta do Snoop.
     * Se o modelo recusar algum ajuste (nível de raciocínio, formato JSON), tenta de novo sem ele.
     */
    static Reply chat(String key, String model, String system, List<Brain.Msg> history,
                      byte[] audio, String audioMime, String text) throws ApiException {
        boolean think = true;
        boolean schema = true;
        int serverRetries = 0;
        JSONObject res;
        while (true) {
            try {
                JSONObject body = chatBody(model, system, history, audio, audioMime, text, think, schema);
                res = post(key, "models/" + model + ":generateContent", body, 60000);
                break;
            } catch (ApiException e) {
                String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                if (e.code == 400 && think && m.contains("think")) {
                    think = false;
                    continue;
                }
                if (e.code == 400 && schema && (m.contains("schema") || m.contains("mime") || m.contains("json"))) {
                    schema = false;
                    continue;
                }
                if ((e.code == 500 || e.code == 503) && serverRetries++ < 1) {
                    SystemClock.sleep(1500);
                    continue;
                }
                throw e;
            }
        }
        return parseReply(res);
    }

    private static JSONObject chatBody(String model, String system, List<Brain.Msg> history, byte[] audio,
                                       String audioMime, String text, boolean think, boolean schema)
            throws ApiException {
        try {
            JSONObject b = new JSONObject();
            b.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", system))));

            JSONArray contents = new JSONArray();
            List<Brain.Msg> msgs = Brain.normalized(history);
            String lastRole = null;
            for (Brain.Msg m : msgs) {
                contents.put(new JSONObject().put("role", m.role)
                        .put("parts", new JSONArray().put(new JSONObject().put("text", m.text))));
                lastRole = m.role;
            }

            JSONArray parts;
            if ("user".equals(lastRole)) {
                // Fala anterior sem resposta: junta à mesma vez do usuário.
                parts = contents.getJSONObject(contents.length() - 1).getJSONArray("parts");
            } else {
                parts = new JSONArray();
                contents.put(new JSONObject().put("role", "user").put("parts", parts));
            }
            if (audio != null) {
                parts.put(new JSONObject().put("text", "Mensagem de voz do seu dono:"));
                parts.put(new JSONObject().put("inlineData", new JSONObject()
                        .put("mimeType", audioMime)
                        .put("data", Base64.encodeToString(audio, Base64.NO_WRAP))));
            } else {
                parts.put(new JSONObject().put("text", text == null ? "" : text));
            }
            b.put("contents", contents);

            JSONObject gc = new JSONObject();
            gc.put("temperature", 0.9);
            gc.put("maxOutputTokens", 2048);
            if (schema) {
                gc.put("responseMimeType", "application/json");
                gc.put("responseSchema", replySchema());
            }
            if (think) {
                gc.put("thinkingConfig", new JSONObject()
                        .put("thinkingLevel", model.contains("lite") ? "minimal" : "low"));
            }
            b.put("generationConfig", gc);
            return b;
        } catch (JSONException e) {
            throw new ApiException(Kind.OTHER, 0, "falha ao montar o pedido");
        }
    }

    private static JSONObject replySchema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("ouvi", new JSONObject().put("type", "STRING")
                .put("description", "O que o dono disse no áudio, transcrito em português. Vazio se não deu para entender."));
        props.put("resposta", new JSONObject().put("type", "STRING")
                .put("description", "O que o Snoop fala de volta, para ser lido em voz alta."));
        props.put("humor", new JSONObject().put("type", "STRING")
                .put("enum", new JSONArray().put("feliz").put("animado").put("curioso").put("calmo")
                        .put("triste").put("preocupado").put("sonolento")));
        props.put("lembrar", new JSONObject().put("type", "STRING")
                .put("description", "Informação nova e duradoura que o dono contou sobre si, ou vazio."));
        props.put("fim", new JSONObject().put("type", "BOOLEAN")
                .put("description", "true só se o dono quiser encerrar a conversa."));
        JSONArray tricks = new JSONArray().put("nenhum");
        for (String t : DogView.TRICKS) tricks.put(t);
        props.put("truque", new JSONObject().put("type", "STRING").put("enum", tricks)
                .put("description", "Truque que o Snoop faz agora (se o dono pedir ou se combinar), ou nenhum."));
        props.put("lembrete", new JSONObject().put("type", "OBJECT")
                .put("description", "Só quando o dono pedir para ser lembrado de algo num horário.")
                .put("properties", new JSONObject()
                        .put("quando", new JSONObject().put("type", "STRING")
                                .put("description", "Data e hora local no formato AAAA-MM-DD HH:MM."))
                        .put("texto", new JSONObject().put("type", "STRING")
                                .put("description", "O que lembrar, curto."))
                        .put("repetir", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("nao").put("diario"))))
                .put("required", new JSONArray().put("quando").put("texto").put("repetir")));
        JSONArray backs = new JSONArray().put("nenhum");
        for (String[] b : Backdrop.PRESETS) backs.put(b[0]);
        backs.put(Backdrop.AUTO);
        props.put("fundo", new JSONObject().put("type", "STRING").put("enum", backs)
                .put("description", "Cor de fundo da tela, só se o dono pedir para trocar; senão nenhum."));
        props.put("cancelar_lembrete", new JSONObject().put("type", "STRING")
                .put("description", "Palavra-chave do lembrete que o dono pediu para cancelar."));
        JSONArray required = new JSONArray().put("ouvi").put("resposta").put("humor").put("lembrar").put("fim")
                .put("truque");
        JSONArray order = new JSONArray().put("ouvi").put("resposta").put("humor").put("lembrar").put("fim")
                .put("truque").put("lembrete").put("cancelar_lembrete").put("fundo");
        return new JSONObject()
                .put("type", "OBJECT")
                .put("properties", props)
                .put("required", required)
                .put("propertyOrdering", order);
    }

    private static Reply parseReply(JSONObject res) throws ApiException {
        JSONObject feedback = res.optJSONObject("promptFeedback");
        if (feedback != null && feedback.has("blockReason")) {
            throw new ApiException(Kind.BLOCKED, 200, feedback.optString("blockReason"));
        }
        JSONArray cands = res.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            throw new ApiException(Kind.OTHER, 200, "o Gemini não mandou resposta");
        }
        JSONObject c0 = cands.optJSONObject(0);
        String finish = c0 == null ? "" : c0.optString("finishReason", "");
        StringBuilder txt = new StringBuilder();
        JSONObject content = c0 == null ? null : c0.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null || p.optBoolean("thought", false)) continue;
                txt.append(p.optString("text", ""));
            }
        }
        String raw = txt.toString().trim();
        if (raw.isEmpty()) {
            if (finish.contains("SAFETY") || finish.contains("PROHIBITED") || finish.contains("BLOCK")) {
                throw new ApiException(Kind.BLOCKED, 200, finish);
            }
            throw new ApiException(Kind.OTHER, 200, "resposta vazia (" + finish + ")");
        }

        Reply r = new Reply();
        JSONObject j = extractJson(raw);
        if (j != null) {
            r.heard = j.optString("ouvi", "").trim();
            r.say = j.optString("resposta", "").trim();
            r.mood = j.optString("humor", "feliz").trim();
            r.remember = j.optString("lembrar", "").trim();
            r.end = j.optBoolean("fim", false);
            String trick = j.optString("truque", "").trim();
            r.trick = "nenhum".equals(Text.fold(trick)) ? "" : trick;
            JSONObject rem = j.optJSONObject("lembrete");
            if (rem != null) {
                r.reminderWhen = rem.optString("quando", "").trim();
                r.reminderText = rem.optString("texto", "").trim();
                r.reminderDaily = "diario".equals(Text.fold(rem.optString("repetir", "")));
            }
            r.cancelReminder = j.optString("cancelar_lembrete", "").trim();
            String back = j.optString("fundo", "").trim();
            r.background = "nenhum".equals(Text.fold(back)) ? "" : back;
        } else {
            r.say = raw;
        }
        if (r.say.isEmpty()) r.say = "Au! Não entendi direito. Fala de novo pra mim?";
        JSONObject usage = res.optJSONObject("usageMetadata");
        if (usage != null) {
            r.tokensIn = usage.optInt("promptTokenCount", 0);
            r.tokensOut = usage.optInt("candidatesTokenCount", 0) + usage.optInt("thoughtsTokenCount", 0);
        }
        return r;
    }

    private static JSONObject extractJson(String s) {
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (JSONException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ voz do Gemini

    /** Gera a fala do Snoop com uma voz do Gemini. Devolve PCM 16 bits mono. */
    static Audio speak(String key, String voice, String text) throws ApiException {
        String styled = "Fale em português do Brasil, com voz animada e carinhosa, como um cachorrinho "
                + "brincalhão conversando com o dono: " + text;
        ApiException last = null;
        for (String model : new String[]{TTS_MODEL, TTS_MODEL_LITE}) {
            try {
                JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                                .put("parts", new JSONArray().put(new JSONObject().put("text", styled)))))
                        .put("generationConfig", new JSONObject()
                                .put("responseModalities", new JSONArray().put("AUDIO"))
                                .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                        .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))));
                Audio a = findAudio(post(key, "models/" + model + ":generateContent", body, 60000));
                if (a != null) return a;
                last = new ApiException(Kind.OTHER, 200, "a voz veio vazia");
            } catch (JSONException e) {
                last = new ApiException(Kind.OTHER, 0, "falha ao montar o pedido de voz");
            } catch (ApiException e) {
                last = e;
                if (e.kind == Kind.KEY || e.kind == Kind.NETWORK) throw e;
            }
            // Formato novo da API (Interactions), caso o antigo não sirva para este modelo.
            try {
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("input", new JSONArray().put(new JSONObject()
                                .put("type", "user_input")
                                .put("content", new JSONArray().put(new JSONObject()
                                        .put("type", "text")
                                        .put("text", text)
                                        .put("annotations", new JSONArray().put(new JSONObject()
                                                .put("type", "speech_metadata")
                                                .put("style", "animado e carinhoso, como um cachorrinho brincalhão")))))))
                        .put("response_format", new JSONObject().put("type", "audio").put("mime_type", "audio/wav"))
                        .put("generation_config", new JSONObject()
                                .put("speech_config", new JSONArray().put(new JSONObject().put("voice", voice))));
                Audio a = findAudio(post(key, "interactions", body, 60000));
                if (a != null) return a;
            } catch (JSONException ignored) {
                // tenta o próximo modelo
            } catch (ApiException e) {
                if (e.kind == Kind.KEY || e.kind == Kind.NETWORK) throw e;
                if (last == null) last = e;
            }
        }
        throw last != null ? last : new ApiException(Kind.OTHER, 0, "sem voz do Gemini");
    }

    /** Procura o primeiro bloco de áudio (base64) em qualquer lugar da resposta. */
    private static Audio findAudio(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String data = o.optString("data", "");
            if (data.length() > 200) {
                String mime = o.optString("mimeType", o.optString("mime_type", ""));
                if (mime.isEmpty() || mime.startsWith("audio")) {
                    try {
                        return decodeAudio(Base64.decode(data, Base64.DEFAULT), mime);
                    } catch (IllegalArgumentException ignored) {
                        // não era base64
                    }
                }
            }
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    Audio a = findAudio(o.opt(names.optString(i)));
                    if (a != null) return a;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Audio r = findAudio(a.opt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** WAV (com cabeçalho RIFF) ou PCM 16 bits cru. */
    static Audio decodeAudio(byte[] raw, String mime) {
        if (raw.length > 44 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') {
            int rate = 24000;
            int pos = 12;
            while (pos + 8 <= raw.length) {
                String id = new String(raw, pos, 4, StandardCharsets.ISO_8859_1);
                int size = le32(raw, pos + 4);
                int start = pos + 8;
                if (id.equals("fmt ") && start + 8 <= raw.length) {
                    rate = le32(raw, start + 4);
                } else if (id.equals("data")) {
                    int len = (size <= 0 || start + size > raw.length) ? raw.length - start : size;
                    byte[] pcm = new byte[len - (len % 2)];
                    System.arraycopy(raw, start, pcm, 0, pcm.length);
                    return new Audio(pcm, rate > 0 ? rate : 24000);
                }
                if (size < 0) break;
                pos = start + size + (size % 2);
            }
        }
        int rate = 24000;
        Matcher m = Pattern.compile("rate=(\\d+)").matcher(mime == null ? "" : mime);
        if (m.find()) {
            try {
                rate = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                rate = 24000;
            }
        }
        byte[] pcm = raw.length % 2 == 0 ? raw : java.util.Arrays.copyOf(raw, raw.length - 1);
        return new Audio(pcm, rate);
    }

    private static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }

    // ------------------------------------------------------------------ HTTP

    private static JSONObject post(String key, String path, JSONObject body, int readTimeoutMs) throws ApiException {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(BASE + path).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(20000);
            con.setReadTimeout(readTimeoutMs);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("x-goog-api-key", key);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = con.getOutputStream()) {
                os.write(out);
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String resp = in == null ? "" : readAll(in);
            if (code >= 200 && code < 300) {
                return new JSONObject(resp);
            }
            throw classify(code, resp);
        } catch (SocketTimeoutException e) {
            throw new ApiException(Kind.NETWORK, 0, "o Gemini demorou demais para responder");
        } catch (UnknownHostException e) {
            throw new ApiException(Kind.NETWORK, 0, "sem internet");
        } catch (IOException e) {
            throw new ApiException(Kind.NETWORK, 0, "sem conexão (" + e.getClass().getSimpleName() + ")");
        } catch (JSONException e) {
            throw new ApiException(Kind.OTHER, 0, "resposta estranha do Gemini");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static ApiException classify(int code, String resp) {
        String message = "";
        String status = "";
        try {
            JSONObject err = new JSONObject(resp).optJSONObject("error");
            if (err != null) {
                message = err.optString("message", "");
                status = err.optString("status", "");
            }
        } catch (JSONException ignored) {
            message = resp == null ? "" : resp;
        }
        if (message.length() > 300) message = message.substring(0, 300) + "…";
        String low = (message + " " + status).toLowerCase(Locale.ROOT);
        Kind kind;
        if (code == 429 || low.contains("resource_exhausted") || low.contains("quota")) {
            kind = Kind.QUOTA;
        } else if (code == 401 || code == 403 || low.contains("api key") || low.contains("api_key")
                || low.contains("permission_denied") || low.contains("unauthenticated")) {
            kind = Kind.KEY;
        } else if (code == 404) {
            kind = Kind.MODEL;
        } else {
            kind = Kind.OTHER;
        }
        String shown = message.isEmpty() ? ("erro " + code) : message;
        return new ApiException(kind, code, shown);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
