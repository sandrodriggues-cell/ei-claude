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

## Limitações

- Depois de reiniciar o celular ou atualizar o app, o Android não deixa o microfone ligar sozinho em segundo plano: toque no aviso do Ei Claude para religar.
- O modelo pequeno em português é leve, mas erra mais que os modelos grandes; use o modo teste e a sensibilidade para calibrar.
