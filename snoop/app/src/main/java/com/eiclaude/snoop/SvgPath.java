package com.eiclaude.snoop;

import android.graphics.Path;

/** Lê traços no formato SVG (M, L, H, V, C, Q, Z) para desenhar o Snoop. */
final class SvgPath {

    private final String d;
    private int pos;

    private SvgPath(String d) {
        this.d = d;
    }

    static Path parse(String d) {
        return new SvgPath(d).run();
    }

    private Path run() {
        Path p = new Path();
        float cx = 0;
        float cy = 0;
        float sx = 0;
        float sy = 0;
        char cmd = 0;
        while (skip()) {
            char ch = d.charAt(pos);
            if (Character.isLetter(ch) && ch != 'e' && ch != 'E') {
                cmd = ch;
                pos++;
            } else if (cmd == 0 || cmd == 'Z' || cmd == 'z') {
                throw new IllegalArgumentException("traço inválido: " + d);
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0;
            float oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    cx = ox + num();
                    cy = oy + num();
                    p.moveTo(cx, cy);
                    sx = cx;
                    sy = cy;
                    cmd = rel ? 'l' : 'L'; // números seguintes são linhas
                    break;
                case 'L':
                    cx = ox + num();
                    cy = oy + num();
                    p.lineTo(cx, cy);
                    break;
                case 'H':
                    cx = ox + num();
                    p.lineTo(cx, cy);
                    break;
                case 'V':
                    cy = oy + num();
                    p.lineTo(cx, cy);
                    break;
                case 'C': {
                    float x1 = ox + num();
                    float y1 = oy + num();
                    float x2 = ox + num();
                    float y2 = oy + num();
                    cx = ox + num();
                    cy = oy + num();
                    p.cubicTo(x1, y1, x2, y2, cx, cy);
                    break;
                }
                case 'Q': {
                    float x1 = ox + num();
                    float y1 = oy + num();
                    cx = ox + num();
                    cy = oy + num();
                    p.quadTo(x1, y1, cx, cy);
                    break;
                }
                case 'Z':
                    p.close();
                    cx = sx;
                    cy = sy;
                    break;
                default:
                    throw new IllegalArgumentException("comando não suportado: " + cmd);
            }
        }
        return p;
    }

    /** Pula espaços e vírgulas; devolve se ainda há algo para ler. */
    private boolean skip() {
        while (pos < d.length()) {
            char ch = d.charAt(pos);
            if (ch == ' ' || ch == ',' || ch == '\n' || ch == '\t' || ch == '\r') pos++;
            else return true;
        }
        return false;
    }

    private float num() {
        skip();
        int start = pos;
        if (pos < d.length() && (d.charAt(pos) == '-' || d.charAt(pos) == '+')) pos++;
        while (pos < d.length()) {
            char ch = d.charAt(pos);
            if (Character.isDigit(ch) || ch == '.') {
                pos++;
            } else if ((ch == 'e' || ch == 'E') && pos + 1 < d.length()) {
                pos++;
                if (d.charAt(pos) == '-' || d.charAt(pos) == '+') pos++;
            } else {
                break;
            }
        }
        if (start == pos) throw new IllegalArgumentException("número esperado em: " + d);
        return Float.parseFloat(d.substring(start, pos));
    }
}
