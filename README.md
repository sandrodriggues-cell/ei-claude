# Ei Claude

App Android de uso pessoal: fica ouvindo uma frase de ativação (por padrão, "Ei, Claude") e, ao ouvir, abre o app do Claude.

- O reconhecimento roda no próprio celular, com o [Vosk](https://alphacephei.com/vosk/) e o modelo pequeno em português. Nenhum áudio sai do aparelho.
- O APK é compilado aqui no GitHub (aba **Actions**) a cada alteração e publicado em **Releases**.

## Instalar

1. Abra a página **Releases** deste repositório no celular e toque no arquivo `EiClaude-vN.apk` mais recente.
2. Autorize o navegador a instalar apps desconhecidos, se o Android pedir.
3. Abra o Ei Claude e siga a lista "Preparação": microfone, notificações, "Aparecer sobre outros apps" e bateria sem restrições.
4. Toque em **Ligar a escuta**.

Versões novas instalam por cima da anterior (a chave de assinatura é fixa).

## Como funciona

- Um serviço em primeiro plano mantém o microfone aberto; por isso aparecem a notificação fixa e o indicador de microfone do Android.
- Ao ouvir a frase, o app vibra, acende a tela, pede o desbloqueio se for preciso e abre o Claude. Lá, um toque na onda sonora inicia o modo de voz.
- Enquanto outro app estiver usando o microfone (o próprio Claude, por exemplo), a escuta fica em pausa.
- **Modo teste:** mostra o que o reconhecedor entende, para ajustar a frase.
- **Sensibilidade:** define a confiança mínima para disparar.

## Modo de voz automático e comando "fechar" (versão 2)

Com a permissão de Acessibilidade ligada (Configurações › Acessibilidade › Apps instalados › Ei Claude), o app:

- toca sozinho no botão do modo de voz depois do "Ei, Claude";
- ao ver a sua fala "fechar" na tela do Claude (frases configuráveis), toca em encerrar e volta para a tela inicial.

O serviço só observa o app do Claude (`com.anthropic.claude`). Se ele não achar os botões, use **Ajustar botões do Claude** para escolher na lista o botão certo, ou copie a lista e mande para o Claude.

No Android 13 ou mais novo, por o app não ter vindo de uma loja, pode ser preciso liberar antes: Configurações › Apps › Ei Claude › ⋮ › Permitir configurações restritas.

O Google Play Protect pode bloquear a instalação de apps baixados pelo navegador que usam Acessibilidade. Nesse caso, a versão anterior continua instalada e funcionando.

## Limitações

- Depois de reiniciar o celular ou atualizar o app, o Android não deixa o microfone ligar sozinho em segundo plano: toque no aviso do Ei Claude para religar.
- O modelo pequeno em português é leve, mas erra mais que os modelos grandes; use o modo teste e a sensibilidade para calibrar.

## App do relógio (Galaxy Watch / Wear OS)

Na pasta `relogio/` fica um app separado para o relógio: você abre, fala, e o Claude responde falando, direto no pulso. Ele conversa com a API do Claude (não usa o app oficial, que não existe para relógio).

- **Abre já ouvindo.** Quando o Claude termina de responder, o relógio volta a ouvir sozinho.
- **"Fechar", "encerrar" ou "tchau"** fecham o app. Tocar na tela interrompe a fala ou para de ouvir.
- O APK é compilado pelo workflow **Compilar APK do relógio** e publicado em **Releases** como `EiClaudeRelogio-vN.apk` (o arquivo do celular continua sendo o `EiClaude-vN.apk`).

### 1. Chave da API do Claude

1. Entre em [platform.claude.com](https://platform.claude.com) com a sua conta e vá em **Settings › API keys › Create key**. A chave começa com `sk-ant-` e só aparece uma vez: copie.
2. A API é cobrada à parte do plano do app. Créditos: **Settings › Billing › Buy credits**. Os planos Max incluem créditos mensais da API (Max 5x: US$ 100; Max 20x: US$ 200), que se ativam em claude.ai › Configurações › Cobrança › API credits › *Link organization*. O plano Pro não inclui.
3. O app usa o Claude Haiku 5.5 por padrão (o mais rápido): US$ 0,10 por milhão de tokens de entrada e US$ 0,50 por milhão de saída, em pedidos de até 100 mil tokens ([preços oficiais](https://platform.claude.com/docs/en/about-claude/pricing)). Os ajustes do relógio mostram o total de tokens usados.

### 2. Instalar no relógio (pelo celular, sem computador)

1. No relógio: **Configurações › Sobre o relógio › Informações do software** e toque várias vezes em **Versão do software** até aparecer o aviso do modo desenvolvedor.
2. Volte às Configurações, abra **Opções do desenvolvedor** (no fim da lista) e ligue **Depuração ADB** e **Depuração sem fio** (pode aparecer como "Depuração por Wi-Fi"). Relógio e celular precisam estar no mesmo Wi-Fi (não use o roteador do próprio celular).
3. No celular, baixe o `EiClaudeRelogio-vN.apk` mais recente na página **Releases** e instale o app **Wear Installer 2** da Play Store.
4. No relógio, em **Depuração sem fio › Parear novo dispositivo**, aparecem um código e um IP:porta. No Wear Installer 2, informe o IP do relógio, toque em **Pair new** e digite o código e a porta do pareamento.
5. Depois conecte com o IP e a porta que aparecem em **Depuração sem fio** (a porta é outra) e instale o APK.
6. Terminou? Desligue a **Depuração ADB** no relógio para poupar bateria.

### 3. Primeira vez

1. Abra o **Ei Claude** no relógio e permita o microfone.
2. A tela de ajustes mostra um endereço como `http://192.168.0.23:8765/1234`. Abra esse endereço no navegador do celular (mesmo Wi-Fi), cole a chave e toque em **Salvar e testar**. Se preferir, dá para digitar a chave no próprio relógio.
3. Quando o teste passa, o relógio diz "Oi! Tudo pronto" e começa a ouvir.

Atalho: **Configurações › Recursos avançados › Personalizar botões › Pressionar duas vezes** e escolha o Ei Claude para abrir com dois toques no botão Home.

### Bom saber

- **Reconhecimento de voz:** o app tenta, nesta ordem, o reconhecedor do sistema, a tela de voz do sistema e, como reserva, o reconhecimento offline (Vosk, o mesmo do app do celular). Os ajustes mostram qual está em uso; dá para forçar o offline.
- **Voz da resposta:** usa o leitor de texto do relógio. Se não ouvir nada, aumente o volume de **Mídia** do relógio.
- **Privacidade:** a chave fica só no relógio. A conversa vai para a API da Anthropic e não aparece no histórico do app do Claude. Depois de 15 minutos parado, a próxima fala começa uma conversa nova.
- **Sem internet em tempo real:** clima, notícias e cotações ficam de fora nesta versão.
