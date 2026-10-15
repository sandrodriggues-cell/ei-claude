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

## App do relógio (Galaxy Watch / Wear OS): controle remoto

Na pasta `relogio/` fica o app do relógio. Ele é um **controle remoto**: com um toque no relógio, o Ei Claude do celular abre o app oficial do Claude já no modo de voz, como quando você diz "Ei, Claude". **Não usa a API e não tem custo extra**: a conversa é a do seu plano, no app do celular.

- **Ao abrir no relógio**, ele já chama o Claude no celular e mostra o andamento: "Abrindo…", "Claude ouvindo no celular" ou o que faltar.
- **Encerrar o Claude** (botão no relógio) fecha o Claude no celular. Dizer "fechar" continua funcionando.
- **A conversa é pelo celular:** você fala com o microfone do celular e a resposta sai nele (ou no fone Bluetooth conectado). O relógio não transmite o áudio do Claude.
- O APK do relógio é o `EiClaudeRelogio-vN.apk` em **Releases** (versão 4 ou mais nova). O do celular continua sendo o `EiClaude-vN.apk` e precisa ser a **v4 ou mais nova**.
- Os dois usam o mesmo id (`com.eiclaude.app`) e a mesma chave: é uma exigência do Google Play Services para o relógio e o celular conversarem. Os números de versão não se cruzam (relógio abaixo de 1000, celular acima), então um APK nunca substitui o outro por engano.

### 1. Atualizar o celular

Instale o `EiClaude-v4.apk` (ou mais novo) por cima do atual, pela página **Releases**. As permissões e a Acessibilidade continuam valendo.

### 2. Instalar no relógio (pelo celular, sem computador)

1. Se você instalou a versão antiga do relógio (a que usava a API), desinstale-a antes: no relógio, **Configurações › Aplicativos › Ei Claude › Desinstalar**.
2. No relógio: **Configurações › Sobre o relógio › Informações do software** e toque várias vezes em **Versão do software** até aparecer o aviso do modo desenvolvedor.
3. Volte às Configurações, abra **Opções do desenvolvedor** (no fim da lista) e ligue **Depuração ADB** e **Depuração sem fio**. Relógio e celular precisam estar no mesmo Wi-Fi (não use o roteador do próprio celular).
4. No celular, baixe o `EiClaudeRelogio-vN.apk` mais recente em **Releases** e instale o app **Wear Installer 2** da Play Store.
5. No relógio, em **Depuração sem fio › Parear novo dispositivo**, aparecem um código e um IP:porta. No Wear Installer 2, informe o IP do relógio, toque em **Pair new** e digite o código e a porta do pareamento.
6. Depois conecte com o IP e a porta que aparecem em **Depuração sem fio** (a porta é outra) e instale o APK.
7. Terminou? Desligue a **Depuração ADB** no relógio para poupar bateria.

### 3. Atalho de dois toques

No relógio: **Configurações › Recursos avançados › Personalizar botões › Pressionar duas vezes** e escolha o **Ei Claude**. Dois toques no botão Início chamam o Claude no celular.

### Bom saber

- **Celular bloqueado:** o Ei Claude acende a tela e pede o desbloqueio; o Claude abre logo depois. Para não precisar desbloquear com o celular no bolso, dá para usar o **Extend Unlock** da Samsung (pode aparecer como Smart Lock) com o relógio como dispositivo confiável (**Configurações › Tela de bloqueio e AOD › Extend Unlock › Dispositivos confiáveis**). Isso deixa o celular menos protegido enquanto o relógio estiver conectado: decida se vale a pena.
- **Mensagens do relógio:** "desatualizado" = falta instalar o Ei Claude v4 no celular; "sem conexão" = o relógio não está ligado ao celular; "Acessibilidade" = ligue a Acessibilidade do Ei Claude no celular para o modo de voz abrir sozinho.
- **Versão antiga com API:** as versões `relogio-v1` a `relogio-v3` conversavam direto no relógio pela API do Claude (cobrada à parte). Não são mais necessárias.

## Snoop: cachorro virtual no relógio (Gemini grátis)

Na pasta `snoop/` fica o **Snoop**, um app separado para o Galaxy Watch: um vira-lata caramelo animado que conversa por voz direto no relógio, sem fone e sem tirar o celular do bolso, e que chama você sozinho de vez em quando.

- **Conversa:** toque no Snoop e fale. A fala vai em áudio para o **Gemini** (plano grátis do Google AI Studio), que entende e responde no jeito do Snoop. A resposta sai em voz alta e ele já volta a ouvir. Toque nele de novo para dizer "pronto" (se ele demorar a perceber que você parou de falar). Diga "tchau" para encerrar.
- **Humor:** ele muda de cara conforme a conversa (feliz, animado, curioso, triste, sonolento…), mexe as orelhas quando ouve e a boca quando fala. Depois de um tempo sem conversa, tira um cochilo.
- **Memória:** lembra das últimas falas e guarda o que você contar sobre você (gostos, rotina, planos). Em **Ajustes › O que o Snoop sabe** dá para ver; **Esquecer conversas e lembranças** apaga tudo.
- **Avisos por conta própria** (vibram no relógio; tocar abre a conversa e ele fala a frase):
  - **Parado:** sem passos por 60 minutos (ajustável), ele chama para se mexer, e comemora quando você se mexe. Não avisa com o relógio carregando.
  - **Saudação** todo dia num horário (padrão 14:00, ajustável).
  - **Saudade:** se ficar 2 dias sem conversar.
  - **Silêncio:** padrão das 08:00 às 14:00 (ajustável). O Não perturbe e o modo dormir do relógio também calam os avisos.
- **Carinho e truques:** deslize o dedo na cabeça dele para fazer carinho (ele fecha os olhos, mostra a língua e solta corações). Peça truques por voz: "senta", "dá a pata", "rola", "late", "pula", "abana o rabo".
- **Lembretes por voz:** "Snoop, me lembra de tomar água às 15h" ou "todo dia às 7h me lembra do remédio". Ele marca no relógio e chama na hora. "Cancela o lembrete da água" desmarca. A lista fica em **Ajustes › Lembretes**.
- **Barriga, passeio e alegria:** três barrinhas embaixo dele. A barriga esvazia com o tempo e enche com **petiscos**, que você ganha andando (1 a cada 1.500 passos no dia, até 6); aparece o botão **Dar petisco**. O passeio mostra quanto você andou nas últimas 3 horas. A alegria sobe com conversa, carinho e truques. Com fome, ele avisa.
- **Turno e folgas:** configure a escala na página do celular (por exemplo 6 dias de trabalho e 2 de folga, a data de uma folga e o horário do turno). Ele deseja bom turno meia hora antes, avisa quando o turno acaba (e quando começa a folga), conta na saudação se hoje é folga e responde "quando é minha próxima folga?".
- **Bloco (tile):** rosto do Snoop, uma frase do momento e o botão **Conversar**.
- **Chamar por voz:** diga "Ok Google, abrir Snoop". Ele abre já ouvindo.
- **Voz:** leitor de texto do relógio (rápido e não gasta o plano grátis) ou **voz do Gemini** (mais natural, leva uns segundos a mais). Se o relógio não tiver voz em português, ele usa a do Gemini sozinho.

### 1. Chave grátis do Gemini

1. No celular, abra **aistudio.google.com/apikey** e entre com sua conta Google.
2. Toque em **Create API key** e copie a chave inteira. **Não publique a chave** em lugar nenhum: ela fica só no relógio.

### 2. Instalar no relógio

O APK é o `Snoop-vN.apk` mais recente em **Releases**. Instale do mesmo jeito que o app do relógio do Ei Claude (seção "Instalar no relógio" acima): modo desenvolvedor, **Depuração ADB** + **Depuração sem fio**, e o **Wear Installer 2** no celular.

### 3. Primeiro uso

1. Abra o **Snoop** no relógio e permita **microfone**, **atividade física** (passos) e **notificações**.
2. Toque em **Ajustes**. Com o relógio e o celular no **mesmo Wi-Fi**, aparece um endereço (`http://…:8765/1234`). Abra esse endereço no navegador do celular, cole a chave e toque em **Salvar e testar**. Nessa página também dá para mudar seu nome, cérebro, voz, horários e avisos.
3. Volte para o Snoop e fale com ele. Para ter o bloco: deslize até o fim dos blocos do relógio, toque em **+** e escolha **Snoop**.

### Bom saber

- **Internet:** o Snoop usa a internet do relógio (pelo celular via Bluetooth ou pelo Wi-Fi).
- **Plano grátis:** tem limite de pedidos por minuto e por dia (os números exatos da sua conta aparecem no AI Studio). Se estourar, ele avisa que "cansou" e volta a funcionar depois. Em **Ajustes** aparece quantos pedidos foram feitos hoje.
- **Cérebro:** **rápido** (Gemini 3.5 Flash-Lite, padrão) ou **esperto** (Gemini 3.8 Flash, pensa mais e demora mais).
- **Privacidade:** a conversa (e as lembranças que vão junto) segue para o Google. No plano grátis, o Google usa esse conteúdo para melhorar os produtos dele e revisores humanos podem ler trechos (desligados da sua conta). O próprio Google pede para não mandar informações sensíveis ou pessoais no plano grátis.
- **Sem internet em tempo real:** clima, notícias e placares ficam de fora; ele avisa quando não sabe.
- **Passos:** vêm do Health Services do Wear OS. Em **Ajustes**, o diagnóstico mostra se está ligado e há quanto tempo você está parado.
