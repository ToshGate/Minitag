## MiniTag v1.4 📁

### Todos os MP3 num só lugar
A conversão deixa de criar uma subpasta `MP3/` ao lado de cada original. Agora há **uma pasta MP3 central**:

- 📂 Escolhe-a (ou cria-a) **uma vez**, na primeira conversão. A app memoriza-a; pode mudá-la no diálogo de conversão, em *Mudar pasta*.
- 🪞 Lá dentro é **recriado o caminho completo** de cada original:
  `Spotiflac/Sam Smith/Álbum/música.flac` → `MP3/Spotiflac/Sam Smith/Álbum/música.mp3`
- 🗂️ Conversões feitas em alturas diferentes, a partir de pastas diferentes, ficam todas organizadas no mesmo sítio.
- 🔁 Nomes repetidos continuam a dar `nome (1).mp3` — nada é sobrescrito, e os originais nunca são alterados.

> **Ao actualizar:** na primeira conversão a app pede a pasta MP3. As subpastas `MP3/` criadas por versões anteriores não são mexidas — pode movê-las ou apagá-las à sua vontade.

### Diálogo de conversão redesenhado
- 🔘 Botões lado a lado: **Converter · Mudar pasta · Cancelar** — deixaram de se empilhar e de obrigar a fazer scroll.
- 📐 A janela tem a altura do conteúdo, até 80% do ecrã, sem espaços em branco; se o conteúdo não couber, só ele faz scroll e os botões ficam sempre visíveis.
- 🔤 Em ecrãs estreitos, o texto de um botão passa para a linha de baixo em vez de ser cortado.

### Desenvolvimento com IA
🤖 Esta app foi desenvolvida com recurso a inteligência artificial (código com o Claude, da Anthropic; ícone com o Gemini, da Google). Foi testada num dispositivo real, mas pode conter erros — reporte-os em *Issues*.

### Instalação
Descarregue **`MiniTag-v1.4-release.apk`** abaixo. Requer Android 8.0 ou superior. Instala por cima da versão anterior se esta tiver sido assinada com a mesma keystore.

Lista completa de alterações em [`CHANGELOG.md`](https://github.com/ToshGate/Minitag/blob/main/CHANGELOG.md). Licença MIT.
