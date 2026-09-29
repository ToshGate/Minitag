## MiniTag v1.2 🎵➡️🎧

### Novidades desde a v1.0

#### 🎧 Conversão para MP3
Seleccione ficheiros e use **Converter seleccionados para MP3**.

- 🎚️ Qualidade à escolha: **320, 256, 192, 128 kbps ou VBR**.
- 📁 Os MP3 ficam numa subpasta **`MP3/`** junto de cada original — **o original nunca é alterado**.
- 🏷️ Tags e capa copiadas automaticamente.
- 🔁 Nomes repetidos não são sobrescritos: é criado `nome (1).mp3`.
- ⚠️ Aviso quando o original já é um formato com perdas (MP3, AAC, Ogg…).
- ⚡ Até **4 ficheiros em simultâneo**, conforme os núcleos do telemóvel.

Converte a partir de FLAC, WAV, AIFF, ALAC, MP3, M4A (AAC) e Ogg Vorbis. WMA e DSF não são suportados (o Android não os descodifica).

#### 🔔 Conversão em segundo plano
- 📱 **Pode sair da app** ou desligar o ecrã — a conversão continua sem ser terminada pelo Android.
- 🔔 **Notificação com progresso** e botão *Cancelar*.
- ✅ Aviso quando termina, se a app não estiver aberta; ao voltar, o resultado aparece no ecrã.

No Android 13+ a app pede autorização para mostrar notificações. Se recusar, a conversão funciona na mesma — só não vê o progresso fora da app.

### Notas
- O codificador é o LAME em Java puro (jump3r), sem bibliotecas nativas: o APK é pequeno e funciona em todos os processadores (arm64, armv7, x86), mas a conversão é mais lenta do que em apps com código nativo.
- Continua **sem permissão de Internet** — tudo acontece no dispositivo.

### Desenvolvimento com IA
🤖 Esta app foi desenvolvida com recurso a inteligência artificial (código com o Claude, da Anthropic; ícone com o Gemini, da Google). Foi testada num dispositivo real, mas pode conter erros — reporte-os em *Issues*.

### Instalação
Descarregue **`MiniTag-v1.2-release.apk`** abaixo. Requer Android 8.0 ou superior. Instala por cima da versão anterior se esta tiver sido assinada com a mesma keystore.

Lista completa de alterações em [`CHANGELOG.md`](https://github.com/ToshGate/Minitag/blob/main/CHANGELOG.md). Licença MIT.
