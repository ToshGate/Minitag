# Changelog

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
