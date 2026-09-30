# Changelog

## [1.3] — 2026-09-30

### Alterado
- Linhas da lista reorganizadas: título, artista, álbum · faixa · ano, e depois **formato · bitrate · kHz · duração · capa**. O caminho do ficheiro, que repetia o nome da música, deixou de aparecer.
- Botões "Abrir pasta" e "Editar seleccionados" com a mesma altura e alinhados.
- MP3 com bitrate variável mostram "VBR" a seguir ao bitrate.
- Quando o jaudiotagger não consegue ler a informação técnica (ex.: M4A com AC-3), é usada a do Android.

## [1.2] — 2026-09-29

### Adicionado
- A conversão para MP3 corre num **serviço em primeiro plano**: continua ao sair da app ou com o ecrã desligado, sem ser terminada pelo Android.
- Notificação com progresso e botão *Cancelar*; aviso de conclusão se a app não estiver aberta.
- Ao voltar à app durante a conversão, o progresso é retomado no ecrã.

### Corrigido
- Falha ao iniciar a conversão no Android 15 (`InvalidForegroundServiceTypeException`): o `ServiceCompat` do androidx.core descartava o tipo de serviço "mediaProcessing".

### Alterado
- O bloqueio das operações de tags passou para o `TagEngine`; a cópia dos ficheiros deixou de ser feita um de cada vez durante a conversão.
- Removida a opção de manter o ecrã ligado durante a conversão (deixou de ser necessária).

## [1.1] — 2026-09-29

### Adicionado
- **Conversão para MP3** dos ficheiros seleccionados:
  - qualidade à escolha: 320, 256, 192, 128 kbps ou VBR (LAME `-V2`); a última escolha é memorizada;
  - gravação numa subpasta `MP3/` junto de cada original, que nunca é alterado;
  - nomes repetidos geram `nome (1).mp3`, `nome (2).mp3`…;
  - tags e capa copiadas para o MP3;
  - aviso quando o original já é um formato com perdas (MP3, AAC, Vorbis…);
  - ficheiros não convertíveis (WMA, DSF, codecs sem descodificador) são indicados e ignorados;
  - vários ficheiros convertidos em simultâneo (um por núcleo livre, até 4);
  - barra de progresso e botão *Cancelar*.
- Dependência jump3r 1.0.5 (LAME em Java, LGPL-2.1+).

## [1.0] — 2026-09-29

Primeira versão estável.

### Adicionado
- Edição em lote com semântica *manter*: campos com valores diferentes mantêm-se se não forem alterados; só os campos modificados são gravados.
- Botão ✕ em cada campo para o apagar (também em lote).
- Campos **Compositor** e **Capa** (ver, substituir, remover; WebP/HEIC convertidos para JPEG).
- Faixa e disco no formato `n/total`.
- Ecrã de detalhe de erros por ficheiro, com *Copiar* e *Repetir*.
- Reabertura automática da última pasta.
- Actualização do MediaStore após gravar.
- Leitura de MP3 só com ID3v1 e sincronização do ID3v1 ao gravar.
- Ícone adaptativo com camada monocromática (ícones temáticos).
- Configuração de assinatura de release via `keystore.properties`.
- Licença MIT.

### Corrigido
- O botão *Editar* não carregava os valores dos ficheiros.
- Editar um ficheiro tocando nele nunca gravava as alterações.
- Erros de leitura/gravação eram ignorados em silêncio.
- Capas em FLAC e Ogg falhavam no Android.
- Ficheiros podiam ficar com lixo no fim quando o novo tag era mais pequeno (escrita sem truncar).
- M4A com áudio AC-3/E-AC-3 (ou sem nº de canais) falhavam com `NullPointerException` — bug do jaudiotagger contornado.
- WAV só com LIST/INFO não guardavam capa nem total de faixas.
- Botões escondidos por baixo da barra de estado no Android 15.
- Sobreposição do rótulo com a indicação *‹vários valores›*.
- Campo Ano não permitia escrever datas completas.
- Botões da capa e do diálogo de erro cortados em ecrãs estreitos.

### Alterado
- Listagem de pastas muito mais rápida (DocumentsContract em vez de DocumentFile).
- Removida a dependência `androidx.documentfile`.

## [0.1]

- Versão inicial: leitura e edição básica de tags.
