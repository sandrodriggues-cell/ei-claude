"""
Gera o mostrador do Snoop (Watch Face Format): imagens do cachorro em cada humor, ícones,
prévia e o res/raw/watchface.xml. Rodar de novo depois de mudar desenhos ou layout:

    pip install cairosvg pillow
    python3 mostrador/gerar.py

Os arquivos gerados ficam versionados; a compilação no GitHub só empacota o que está aqui.
"""
import base64
import io
import os

import cairosvg
from PIL import Image, ImageEnhance

AQUI = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(AQUI, "app", "src", "main", "res")
IMG = os.path.join(RES, "drawable-nodpi")

# ------------------------------------------------------------------ desenho (mesmo do app)

FUR, FUR_LIGHT, FUR_DARK = "#D88C43", "#E9A862", "#B96A2B"
EAR, EAR_FOLD, CREAM, INK = "#A95C24", "#874519", "#F7E0BC", "#2A1C14"
MOUTH, TONGUE, COLLAR, TAG, BLUSH, HEART = "#5A2620", "#EE7C8B", "#2F80ED", "#F2C14E", "#F49A8C", "#FF6B8B"

HEAD = "M 0 -50 C 27 -50 51 -40 54 -12 C 57 12 48 34 27 42 C 14 47 -14 47 -27 42 C -48 34 -57 12 -54 -12 C -51 -40 -27 -50 0 -50 Z"
BODY = "M -28 28 C -40 50 -50 72 -56 130 L 56 130 C 50 72 40 50 28 28 Z"
CHEST = "M -15 50 C -13 70 -18 90 -26 130 L 26 130 C 18 90 13 70 15 50 Z"
COLLAR_P = "M -33 39 C -14 50 14 50 33 39 L 35 48 C 14 60 -14 60 -35 48 Z"
EAR_L = "M 12 -2 C 0 -13 -20 -12 -29 4 C -38 22 -37 50 -27 61 C -19 69 -8 63 -4 48 C 0 33 9 15 12 -2 Z"
EAR_R = "M -16 6 C -12 -14 -1 -33 15 -44 C 26 -32 28 -10 21 9 Z"
EAR_R_FOLD = "M 15 -44 C 21 -38 25 -31 27 -26 C 30 -18 31 -12 30 -6 C 23 -12 14 -22 6 -32 C 9 -37 12 -41 15 -44 Z"
BLAZE = "M -5 -35 C -3 -41 3 -41 5 -35 L 11 6 L -11 6 Z"
NOSE = "M -9 7 C -9 1 9 1 9 7 C 9 12 3 15 0 15 C -3 15 -9 12 -9 7 Z"
SMILE = "M -12 23 Q -6 28 0 21 Q 6 28 12 23"
FROWN = "M -10 28 Q 0 21 10 28"
HEART_P = "M 0 4 C -7 -1 -5 -8 0 -4 C 5 -8 7 -1 0 4 Z"


def dog_svg(estado):
    """estado: normal, feliz, preguica, dormindo."""
    ear_l, ear_r = {"normal": (0, 0), "feliz": (10, -4), "preguica": (-12, 15), "dormindo": (-6, 10)}[estado]
    p = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="-106 -108 212 212" width="600" height="600">',
         '<defs><radialGradient id="g" cx="-14" cy="-30" r="80" gradientUnits="userSpaceOnUse">'
         f'<stop offset="0" stop-color="{FUR_LIGHT}"/><stop offset="0.6" stop-color="{FUR}"/>'
         f'<stop offset="1" stop-color="{FUR_DARK}"/></radialGradient></defs>',
         f'<path d="{BODY}" fill="{FUR}"/>', f'<path d="{CHEST}" fill="{CREAM}"/>',
         f'<path d="{COLLAR_P}" fill="{COLLAR}"/>',
         f'<circle cx="0" cy="60" r="7" fill="{TAG}" stroke="#C99A2E" stroke-width="1.2"/>',
         f'<g transform="translate(-36 -34) rotate({ear_l})"><path d="{EAR_L}" fill="{EAR}"/></g>',
         f'<g transform="translate(32 -38) rotate({ear_r})"><path d="{EAR_R}" fill="{EAR}"/>'
         f'<path d="{EAR_R_FOLD}" fill="{EAR_FOLD}"/></g>',
         f'<path d="{HEAD}" fill="url(#g)"/>', f'<path d="{BLAZE}" fill="{CREAM}"/>',
         f'<ellipse cx="0" cy="23" rx="29" ry="20" fill="{CREAM}"/>']
    for s in (-1, 1):
        rot = 16 * s if estado == "preguica" else 0
        dy = -3 if estado == "feliz" else 0
        p.append(f'<ellipse cx="{20 * s}" cy="{-24 + dy}" rx="6" ry="3.6" fill="{CREAM}" '
                 f'transform="rotate({rot} {20 * s} {-24 + dy})"/>')
        cx, cy = 20 * s, -7
        if estado == "feliz":
            p.append(f'<path d="M {cx - 7} {cy + 3} Q {cx} {cy - 8} {cx + 7} {cy + 3}" stroke="{INK}" '
                     'stroke-width="3.4" fill="none" stroke-linecap="round"/>')
        elif estado == "dormindo":
            p.append(f'<path d="M {cx - 7} {cy} Q {cx} {cy + 6} {cx + 7} {cy}" stroke="{INK}" '
                     'stroke-width="3" fill="none" stroke-linecap="round"/>')
        else:
            p.append(f'<ellipse cx="{cx}" cy="{cy}" rx="7" ry="8" fill="{INK}"/>')
            p.append(f'<circle cx="{cx + 2.4}" cy="{cy - 3}" r="2.4" fill="#fff"/>')
            p.append(f'<circle cx="{cx - 2.2}" cy="{cy + 2.6}" r="1" fill="#fff" fill-opacity="0.8"/>')
            if estado == "preguica":  # pálpebras caídas
                p.append(f'<clipPath id="olho{s}"><ellipse cx="{cx}" cy="{cy}" rx="7" ry="8"/></clipPath>')
                p.append(f'<rect x="{cx - 8}" y="{cy - 9}" width="16" height="6" fill="{FUR}" clip-path="url(#olho{s})"/>')
    p.append(f'<path d="{NOSE}" fill="{INK}"/>')
    p.append('<ellipse cx="-3" cy="5" rx="3" ry="1.6" fill="#fff" fill-opacity="0.35"/>')
    p.append(f'<path d="M 0 15 L 0 21" stroke="{INK}" stroke-width="2.2" stroke-linecap="round"/>')
    if estado == "preguica":
        p.append(f'<path d="{FROWN}" stroke="{INK}" stroke-width="2.4" fill="none" stroke-linecap="round"/>')
    else:
        if estado == "feliz":
            p.append(f'<path d="M -6 24 L 6 24 L 6 33 C 6 39 -6 39 -6 33 Z" fill="{TONGUE}"/>')
            p.append('<path d="M 0 26 L 0 33" stroke="#D45E6F" stroke-width="1.2" stroke-linecap="round"/>')
        p.append(f'<path d="{SMILE}" stroke="{INK}" stroke-width="2.4" fill="none" '
                 'stroke-linecap="round" stroke-linejoin="round"/>')
    if estado in ("feliz", "normal"):
        p.append(f'<circle cx="-35" cy="14" r="6" fill="{BLUSH}" fill-opacity="{0.45 if estado == "feliz" else 0.25}"/>')
        p.append(f'<circle cx="35" cy="14" r="6" fill="{BLUSH}" fill-opacity="{0.45 if estado == "feliz" else 0.25}"/>')
    if estado == "feliz":
        p.append(f'<g transform="translate(-58 -62) scale(1.2)"><path d="{HEART_P}" fill="{HEART}"/></g>')
        p.append(f'<g transform="translate(60 -78) scale(0.9)"><path d="{HEART_P}" fill="{HEART}" fill-opacity="0.8"/></g>')
    if estado == "dormindo":
        for k, (x, y, size) in enumerate([(48, -62, 12), (59, -74, 15), (71, -86, 18)]):
            p.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="bold" font-family="sans-serif" '
                     f'fill="#F5F1EC" fill-opacity="{0.55 + 0.15 * k}">z</text>')
    p.append('</svg>')
    return "\n".join(p)


def render_png(svg, size):
    data = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(data)).convert("RGBA")


def fade_bottom(img, start=0.70):
    """O peito some suavemente embaixo (sem corte reto)."""
    w, h = img.size
    a = img.getchannel("A")
    px = a.load()
    y0 = int(h * start)
    for y in range(y0, h):
        f = 1 - (y - y0) / (h - y0)
        for x in range(w):
            px[x, y] = int(px[x, y] * f)
    img.putalpha(a)
    return img


# ------------------------------------------------------------------ cores (mesmas do app)

# id, nome, centro, borda, texto, texto suave, destaque
FUNDOS = [
    ("noite", "Noite", "#1B2A4A", "#070B16", "#F5F1EC", "#B8C2D6", "#E9A862"),
    ("preto", "Preto", "#000000", "#000000", "#F5F1EC", "#A79F96", "#E9A862"),
    ("parque", "Parque", "#24452C", "#0A160D", "#F5F1EC", "#B5C9B4", "#E9A862"),
    ("por_do_sol", "Pôr do sol", "#5A2A1A", "#160806", "#F5F1EC", "#D9B8A6", "#F2C14E"),
    ("lavanda", "Lavanda", "#3A2C55", "#120C1D", "#F5F1EC", "#C4B8D9", "#E9A862"),
    ("oceano", "Oceano", "#0F4552", "#041418", "#F5F1EC", "#A9CDD4", "#E9A862"),
    ("vinho", "Vinho", "#4A1A2C", "#14060C", "#F5F1EC", "#D6AEBC", "#E9A862"),
    ("ceu", "Céu", "#D6ECFF", "#8FC1EE", "#1E2A3A", "#3D5168", "#B65A14"),
    ("areia", "Areia", "#F6EBDA", "#E0C8A4", "#2A1C14", "#5E4A3A", "#B65A14"),
]

SONECA = [  # id, nome, expressão (hora em que ele dorme)
    ("dia", "Das 8h às 14h", "[HOUR_0_23] >= 8 && 14 > [HOUR_0_23]"),
    ("noite", "Das 23h às 6h", "[HOUR_0_23] >= 23 || 6 > [HOUR_0_23]"),
    ("nunca", "Nunca", "1 == 0"),
]

FELIZ = "[STEP_GOAL] > 0 && [STEP_COUNT] >= [STEP_GOAL]"
PREGUICA = "[HOUR_0_23] >= 17 && 30 > [STEP_PERCENT]"

APP = "com.eiclaude.snoop/com.eiclaude.snoop.MainActivity"
DOG_X, DOG_Y, DOG_S = 85, 128, 280


def color(h):
    return "#FF" + h[1:].upper()


def dog_condition(sleep_expr, indent):
    i = " " * indent

    def part(estado):
        return (f'{i}    <PartImage name="snoop_{estado}" x="0" y="0" width="{DOG_S}" height="{DOG_S}">\n'
                f'{i}      <Image resource="snoop_{estado}"/>\n'
                f'{i}    </PartImage>\n')

    return (f'{i}<Condition>\n'
            f'{i}  <Expressions>\n'
            f'{i}    <Expression name="dormindo"><![CDATA[{sleep_expr}]]></Expression>\n'
            f'{i}    <Expression name="feliz"><![CDATA[{FELIZ}]]></Expression>\n'
            f'{i}    <Expression name="preguica"><![CDATA[{PREGUICA}]]></Expression>\n'
            f'{i}  </Expressions>\n'
            f'{i}  <Compare expression="dormindo">\n{part("dormindo")}{i}  </Compare>\n'
            f'{i}  <Compare expression="feliz">\n{part("feliz")}{i}  </Compare>\n'
            f'{i}  <Compare expression="preguica">\n{part("preguica")}{i}  </Compare>\n'
            f'{i}  <Default>\n{part("normal")}{i}  </Default>\n'
            f'{i}</Condition>\n')


def themed_group(f):
    """Fundo, anel, hora, data, passos e bateria com as cores de um tema (tudo em hexadecimal:
    funciona em qualquer versão do Watch Face Format)."""
    fid, _, c0, c1, ink, soft, acc = f
    return f"""          <Group name="tema_{fid}" x="0" y="0" width="450" height="450">
            <Variant mode="AMBIENT" target="alpha" value="0"/>
            <PartDraw name="fundo_{fid}" x="0" y="0" width="450" height="450">
              <Rectangle x="0" y="0" width="450" height="450">
                <Fill color="{color(c1)}">
                  <RadialGradient centerX="225" centerY="215" radius="300" colors="{color(c0)} {color(c1)}" positions="0 1"/>
                </Fill>
              </Rectangle>
            </PartDraw>
            <PartDraw name="anel_{fid}" x="0" y="0" width="450" height="450">
              <Arc centerX="225" centerY="225" width="434" height="434" startAngle="0" endAngle="360">
                <Stroke color="#40909090" thickness="6" cap="ROUND"/>
              </Arc>
              <Arc centerX="225" centerY="225" width="434" height="434" startAngle="0" endAngle="0">
                <Transform target="endAngle" value="clamp([STEP_PERCENT], 0, 100) * 3.6"/>
                <Stroke color="{color(acc)}" thickness="6" cap="ROUND"/>
              </Arc>
            </PartDraw>
            <DigitalClock x="75" y="20" width="300" height="96">
              <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="300" height="96">
                <Font family="SYNC_TO_DEVICE" size="80" weight="SEMI_BOLD" color="{color(ink)}"/>
              </TimeText>
            </DigitalClock>
            <PartText name="data_{fid}" x="75" y="108" width="300" height="34">
              <Text align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="22" weight="NORMAL" color="{color(soft)}">
                  <Template>%s, %s %s<Parameter expression="[DAY_OF_WEEK_S]"/><Parameter expression="[DAY]"/><Parameter expression="[MONTH_S]"/></Template>
                </Font>
              </Text>
            </PartText>
            <PartImage name="icone_passos_{fid}" x="111" y="392" width="24" height="24" tintColor="{color(acc)}">
              <Image resource="icone_passos"/>
            </PartImage>
            <PartText name="passos_{fid}" x="139" y="384" width="90" height="40">
              <Text align="START">
                <Font family="SYNC_TO_DEVICE" size="24" weight="MEDIUM" color="{color(ink)}">
                  <Template>%s<Parameter expression="[STEP_COUNT]"/></Template>
                </Font>
              </Text>
            </PartText>
            <PartImage name="icone_bateria_{fid}" x="243" y="392" width="24" height="24" tintColor="{color(acc)}">
              <Launch target="BATTERY_STATUS"/>
              <Image resource="icone_bateria"/>
            </PartImage>
            <PartText name="bateria_{fid}" x="269" y="384" width="48" height="40">
              <Text align="END">
                <Font family="SYNC_TO_DEVICE" size="24" weight="MEDIUM" color="{color(ink)}">
                  <Template>%s<Parameter expression="[BATTERY_PERCENT]"/></Template>
                </Font>
              </Text>
            </PartText>
            <PartText name="bateria_pct_{fid}" x="318" y="384" width="27" height="40">
              <Text align="START">
                <Font family="SYNC_TO_DEVICE" size="20" weight="MEDIUM" color="{color(soft)}">%</Font>
              </Text>
            </PartText>
          </Group>
"""


def watchface_xml():
    fundo_opts = "\n".join(f'      <ListOption id="{f[0]}" displayName="@string/fundo_{f[0]}"/>' for f in FUNDOS)
    fundo_scene = "".join(f'        <ListOption id="{f[0]}">\n{themed_group(f)}        </ListOption>\n' for f in FUNDOS)
    sono = "\n".join(f'      <ListOption id="{s[0]}" displayName="@string/soneca_{s[0]}"/>' for s in SONECA)
    sono_scene = "".join(
        f'        <ListOption id="{s[0]}">\n{dog_condition(s[2], 10)}        </ListOption>\n' for s in SONECA)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por mostrador/gerar.py: edite lá e gere de novo. -->
<WatchFace width="450" height="450" clipShape="CIRCLE">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="10:08:32"/>
  <UserConfigurations>
    <ListConfiguration id="fundo" displayName="@string/cfg_fundo" defaultValue="noite">
{fundo_opts}
    </ListConfiguration>
    <ListConfiguration id="soneca" displayName="@string/cfg_soneca" defaultValue="dia">
{sono}
    </ListConfiguration>
  </UserConfigurations>
  <Scene backgroundColor="#FF000000">

    <!-- Cores escolhidas: fundo, anel de passos, hora, data, passos e bateria (somem no modo sempre ligado). -->
    <Group name="tema" x="0" y="0" width="450" height="450">
      <ListConfiguration id="fundo">
{fundo_scene}      </ListConfiguration>
    </Group>

    <!-- Modo sempre ligado: só a hora, fina e clara. -->
    <DigitalClock x="75" y="20" width="300" height="96" alpha="0">
      <Variant mode="AMBIENT" target="alpha" value="255"/>
      <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="300" height="96">
        <Font family="SYNC_TO_DEVICE" size="80" weight="LIGHT" color="#FFE6E6E6"/>
      </TimeText>
    </DigitalClock>

    <!-- O Snoop: humor pelos passos e pela hora; tocar nele abre o app para conversar. -->
    <Group name="snoop" x="{DOG_X}" y="{DOG_Y}" width="{DOG_S}" height="{DOG_S}">
      <Launch target="{APP}"/>
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <ListConfiguration id="soneca">
{sono_scene}      </ListConfiguration>
    </Group>
    <PartImage name="snoop_cochilo" x="{DOG_X}" y="{DOG_Y}" width="{DOG_S}" height="{DOG_S}" alpha="0">
      <Variant mode="AMBIENT" target="alpha" value="255"/>
      <Image resource="snoop_ambiente"/>
    </PartImage>
  </Scene>
</WatchFace>
"""


def strings_xml():
    linhas = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>',
              '    <string name="app_name">Snoop</string>',
              '    <string name="cfg_fundo">Fundo</string>',
              '    <string name="cfg_soneca">Hora do cochilo</string>']
    for f in FUNDOS:
        linhas.append(f'    <string name="fundo_{f[0]}">{f[1]}</string>')
    for s in SONECA:
        linhas.append(f'    <string name="soneca_{s[0]}">{s[1]}</string>')
    linhas.append('</resources>')
    return "\n".join(linhas) + "\n"


# ------------------------------------------------------------------ ícones e prévia

PAW_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="96" height="96" fill="#FFFFFF">'
           '<path d="M12,12.5 C9.2,12.5 6.5,15.4 6.5,17.6 C6.5,19.3 7.9,20 9.2,20 C10.4,20 11.1,19.4 12,19.4 '
           'C12.9,19.4 13.6,20 14.8,20 C16.1,20 17.5,19.3 17.5,17.6 C17.5,15.4 14.8,12.5 12,12.5 Z"/>'
           '<ellipse cx="5.5" cy="11.7" rx="2" ry="2.5"/><ellipse cx="18.5" cy="11.7" rx="2" ry="2.5"/>'
           '<ellipse cx="9.2" cy="7.1" rx="2.1" ry="2.6"/><ellipse cx="14.8" cy="7.1" rx="2.1" ry="2.6"/></svg>')
BATTERY_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="96" height="96">'
               '<rect x="3" y="7" width="16" height="10" rx="2.5" fill="none" stroke="#FFFFFF" stroke-width="2"/>'
               '<rect x="20" y="10" width="2" height="4" rx="1" fill="#FFFFFF"/>'
               '<rect x="5.5" y="9.5" width="9" height="5" rx="1" fill="#FFFFFF"/></svg>')


def tinted_icon_b64(svg, hex_color):
    img = render_png(svg, 48)
    r, g, b = int(hex_color[1:3], 16), int(hex_color[3:5], 16), int(hex_color[5:7], 16)
    solid = Image.new("RGBA", img.size, (r, g, b, 255))
    solid.putalpha(img.getchannel("A"))
    buf = io.BytesIO()
    solid.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode()


def preview(dog_png):
    f = FUNDOS[0]
    b64 = base64.b64encode(dog_png).decode()
    paw = tinted_icon_b64(PAW_SVG, f[6])
    bat = tinted_icon_b64(BATTERY_SVG, f[6])
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 450 450" width="450" height="450">
<defs><radialGradient id="bg" cx="225" cy="215" r="300" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="{f[2]}"/><stop offset="1" stop-color="{f[3]}"/></radialGradient>
<clipPath id="c"><circle cx="225" cy="225" r="225"/></clipPath></defs>
<g clip-path="url(#c)"><rect width="450" height="450" fill="url(#bg)"/>
<circle cx="225" cy="225" r="217" fill="none" stroke="#909090" stroke-opacity="0.25" stroke-width="6"/>
<path d="M 225 8 A 217 217 0 1 1 30 320" fill="none" stroke="{f[6]}" stroke-width="6" stroke-linecap="round"/>
<text x="225" y="98" font-size="80" font-weight="600" text-anchor="middle" font-family="sans-serif" fill="{f[4]}">10:08</text>
<text x="225" y="134" font-size="22" text-anchor="middle" font-family="sans-serif" fill="{f[5]}">qui, 15 out</text>
<image x="{DOG_X}" y="{DOG_Y}" width="{DOG_S}" height="{DOG_S}" xlink:href="data:image/png;base64,{b64}"/>
<image x="111" y="392" width="24" height="24" xlink:href="data:image/png;base64,{paw}"/>
<text x="139" y="413" font-size="24" font-family="sans-serif" fill="{f[4]}">6240</text>
<image x="243" y="392" width="24" height="24" xlink:href="data:image/png;base64,{bat}"/>
<text x="317" y="413" font-size="24" font-family="sans-serif" fill="{f[4]}" text-anchor="end">82</text>
<text x="319" y="413" font-size="20" font-family="sans-serif" fill="{f[5]}">%</text>
</g></svg>'''
    return render_png(svg, 450)


def main():
    os.makedirs(IMG, exist_ok=True)
    pngs = {}
    for estado in ("normal", "feliz", "preguica", "dormindo"):
        img = fade_bottom(render_png(dog_svg(estado), DOG_S))
        img.save(os.path.join(IMG, f"snoop_{estado}.png"), optimize=True)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        pngs[estado] = buf.getvalue()
    # Modo sempre ligado: cochilando, sem cor e mais escuro (poucos pixels acesos).
    amb = Image.open(io.BytesIO(pngs["dormindo"])).convert("RGBA")
    alpha = amb.getchannel("A")
    gray = ImageEnhance.Brightness(amb.convert("L").convert("RGBA")).enhance(0.55)
    gray.putalpha(alpha)
    gray.save(os.path.join(IMG, "snoop_ambiente.png"), optimize=True)

    render_png(PAW_SVG, 48).save(os.path.join(IMG, "icone_passos.png"), optimize=True)
    render_png(BATTERY_SVG, 48).save(os.path.join(IMG, "icone_bateria.png"), optimize=True)
    preview(pngs["normal"]).save(os.path.join(IMG, "preview.png"), optimize=True)

    with open(os.path.join(RES, "raw", "watchface.xml"), "w", encoding="utf-8") as fh:
        fh.write(watchface_xml())
    with open(os.path.join(RES, "values", "strings.xml"), "w", encoding="utf-8") as fh:
        fh.write(strings_xml())
    print("ok")


if __name__ == "__main__":
    main()
