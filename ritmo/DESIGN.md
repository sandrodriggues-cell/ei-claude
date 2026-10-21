# Feito: guia de padrões visuais

Medido nas capturas do Any.do enviadas em 20/10/2026 (tela externa do Galaxy Z Fold:
904 × 2316 px, densidade 2,625, ou seja, **344 × 882 dp**). Os tamanhos de fonte saíram
renderizando a Inter em tamanhos candidatos, na mesma densidade, e comparando a altura e a
largura de cada texto com a captura. No app Android, 1 px de CSS = 1 dp (`setTextZoom(100)`).

## Fonte

**Inter** em todas as telas (no Android vai embutida no APK; no Claude vem do Google Fonts).

| Uso | Tamanho (px = dp) | Peso | Cor |
|---|---|---|---|
| Título da tela, em maiúsculas, até 2 linhas ("TODAS AS / TAREFAS") | 16 / linha 19,5 | 700 | #000 |
| Título do mês no calendário ("out. de 2026") | 16 | 700 | #000 |
| Seções e itens principais ("Hoje", "Amanhã", "Em breve", "Outro dia") | 19 | 700 | #000 |
| Texto e placeholder da barra ("Eu quero…") | 16 | 400 | #000 / #9B9B9B |
| Nome da tarefa | 16 / linha 22 | 400 | #000 |
| Datas do calendário | 14 | 400 | #000 (fora do mês #CCC) |
| Dias da semana, linha de detalhes | 13 | 400 | #333 / #9197A3 |
| Rótulo de dia em maiúsculas ("HOJE, TERÇA-FEIRA, 20 DE OUTUBRO") | 11,25 | 600 | #9197A3 |
| Horas da régua ("18:00") | 10 | 400 | #DCDCDC |

Os números batem com a captura em altura e largura ao mesmo tempo (diferença abaixo de 1 dp) e
são valores redondos, ou seja, o aparelho está com a fonte no tamanho padrão.

## Cores (tema claro)

| Token | Cor | Onde |
|---|---|---|
| fundo | #FFFFFF | tudo |
| tinta | #000000 | títulos, tarefas, datas |
| tinta 2 | #333333 | dias da semana |
| apagado | #9197A3 | rótulos e detalhes |
| placeholder | #9B9B9B | barra "Eu quero…" |
| ícones | #B2B2B2 | quadrado, menu, "…", "+" |
| fraco | #CCCCCC | dias de outro mês, chevrons |
| horas | #DCDCDC | régua de horas |
| linha | #ECECEC | linhas de hora |
| linha 2 / 3 | #F2F2F2 / #F9F9F9 | borda da caixa "dia inteiro" / meia hora |
| preenchimento | #FAFAFA | caixa "dia inteiro" |
| **azul** | **#0083FF** | hoje, contadores, ícone Início, ações |
| agora | #F0063C | linha da hora atual e atrasadas |

## Medidas (dp)

- Margem lateral: **16**. Ícones em caixas de 24, botões de toque de 40.
- Barra superior: **56** de altura. Ícone da esquerda centrado em x = 28; o segundo em x = 70; o "…" em x = largura − 28. Título centrado numa coluna de ~144 de largura, quebrando em 2 linhas.
- Ícones da barra: quadrado de 19 (traço 1,6, canto 3,8), menu de 3 linhas de 15,6 × 13,3, círculo "…" de 21,7, "+" de 16,4, todos em #B2B2B2.
- Seções: linha de **52**, primeira 10 abaixo da barra. À direita: "+" cinza (vazia ou aberta) ou contador azul (círculo de 24, traço 1,25, número 13) quando fechada.
- Calendário: linha dos dias da semana e cada semana do mês com **30** de altura, sempre 6 semanas, 7 colunas iguais de borda a borda; hoje = círculo azul cheio de 30 com número branco em negrito.
- Rótulo do dia: faixa de 34. Caixa "dia inteiro": começa em x = 50, altura 27, borda #F2F2F2, fundo #FAFAFA. Abaixo, faixa de 29,5 com o chevron na coluna das horas.
- Régua: coluna de horas de **50**, cada hora com **51**, rótulo centrado na linha. Linha de agora com 1,15 de espessura e bolinha de 6 na borda da régua.
- Sombras: o bloco do mês projeta uma sombra suave de ~9 (3,5 %); o cabeçalho do dia, uma de ~3 (7 %).
- Base da tela: botão Início de 56 em x = 16 (ícone azul de 20 × 21, traço 1,5) e a pílula "Eu quero…" de x = 80 até 16 da borda, **56** de altura, cantos de 28, branca com sombra para baixo; texto começa 24 dentro da pílula; ícone à direita centrado 36 antes da borda. A pílula fica 24 acima da barra do sistema.

## Estrutura

- Sem barra de abas: a navegação sai do botão **Início** (Meu dia, Todas as tarefas, Próximos 7 dias, Calendário, Hábitos, listas, etiquetas, Concluídas, Ajustes).
- A barra **"Eu quero…"** cria o que faz sentido na tela: tarefa (listas e Meu dia), evento (calendário) ou hábito (hábitos). Ao tocar, aparecem os atalhos Mais tarde hoje, Amanhã, Próxima semana, Algum dia e Personalizado.
- Todas as tarefas sempre mostra as quatro seções do Any.do: **Hoje** (com as atrasadas), **Amanhã**, **Em breve** e **Outro dia** (sem data).
- O quadrado no canto esquerdo mostra ou esconde as concluídas; o "…" abre as opções da tela; no calendário, o menu de 3 linhas troca a visualização (Agenda, Dia, 3 dias, Semana) e o título abre o mês.
