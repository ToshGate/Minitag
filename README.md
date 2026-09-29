<p align="center">
  <img src="docs/icon-512.png" width="128" alt="Ícone do MiniTag">
</p>

<h1 align="center">MiniTag</h1>

<p align="center">
  Editor de metadados de áudio para Android, inspirado no Mp3tag.<br>
  Lê e grava tags directamente nos ficheiros, um a um ou em lote.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Versão" src="https://img.shields.io/badge/versão-1.0-blue">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Licença MIT" src="https://img.shields.io/badge/licença-MIT-green">
  <img alt="Desenvolvido com IA" src="https://img.shields.io/badge/desenvolvido%20com-IA-orange">
</p>

> 🤖 **Aviso:** esta app foi desenvolvida com recurso a inteligência artificial. Ver [Desenvolvimento com IA](#desenvolvimento-com-ia).

---

## Funcionalidades

- **Edição individual** — toque num ficheiro para ver e alterar as tags actuais.
- **Edição em lote** — seleccione vários ficheiros (toque longo ou caixa) e edite-os de uma vez:
  - campos iguais em todos aparecem preenchidos;
  - campos diferentes mostram *‹vários valores›* e **mantêm-se** se não lhes tocar;
  - só o que alterar é gravado; o ✕ apaga um campo em todos os ficheiros.
- **Capa do álbum** — ver, substituir ou remover. Imagens WebP/HEIC são convertidas para JPEG.
- **Faixa e disco** no formato `3` ou `3/12` (número e total).
- **Ano** aceita ano simples (`2008`) ou data completa (`2008-06-23`).
- Pesquisa recursiva de subpastas e reabertura automática da última pasta.
- Detalhes completos de erro por ficheiro, com opção de copiar e repetir a leitura.
- Actualiza a biblioteca de música do Android após gravar, para os leitores verem logo as alterações.
- Ícone adaptativo com suporte a ícones temáticos (Android 13+).
- **Sem permissão de Internet** — tudo acontece no dispositivo.

### Campos suportados

Título · Artista · Álbum · Artista do álbum · Compositor · Ano · Faixa · Disco · Género · Comentário · Capa

### Formatos suportados

| Formato | Extensões | Tipo de tag |
|---|---|---|
| MP3 | `.mp3` | ID3v2 (ID3v1 é lido e mantido sincronizado) |
| FLAC | `.flac` | Vorbis Comments + bloco PICTURE |
| Ogg Vorbis | `.ogg`, `.oga` | Vorbis Comments |
| MPEG-4 Audio | `.m4a` | MP4 (iTunes) |
| WAV | `.wav` | ID3 + LIST/INFO sincronizados |
| AIFF | `.aif`, `.aiff` | ID3 |
| WMA | `.wma` | ASF |
| DSF | `.dsf` | ID3 |

## Capturas de ecrã

<!-- Adicionar imagens em docs/screenshots/ e descomentar:
<p align="center">
  <img src="docs/screenshots/lista.png" width="260">
  <img src="docs/screenshots/editar.png" width="260">
  <img src="docs/screenshots/lote.png" width="260">
</p>
-->

## Instalação

1. Descarregue o `MiniTag-v1.0-release.apk` na página de [Releases](../../releases).
2. Abra o ficheiro no telemóvel e autorize a instalação de fontes desconhecidas, se for pedido.

Requer **Android 8.0 (API 26)** ou superior.

## Utilização

1. **Abrir pasta** e escolha a pasta de música (o Android pede autorização de acesso uma vez).
2. **Toque** numa música para a editar, ou **toque longo** para seleccionar várias e use **Editar seleccionados**.
3. Altere os campos e carregue em **Gravar** — as alterações são escritas logo no ficheiro.

> **Nota:** a partir do Android 11 o sistema não permite escolher a raiz do armazenamento nem a pasta *Download* completa. Escolha uma subpasta (ex.: `Music`).

## Limitações conhecidas

- **AAC (`.aac`) e Opus (`.opus`)** ainda não são suportados.
- Para ler/gravar, cada ficheiro é copiado temporariamente para a cache da app (exigência do jaudiotagger), o que torna mais lentas pastas com ficheiros muito grandes (ex.: FLAC de alta resolução).
- Campos com vários valores (ex.: vários artistas) são mostrados e gravados como um único valor.

## Compilar a partir do código

Requisitos: Android Studio (recente) com JDK 17.

```bash
git clone https://github.com/<utilizador>/MiniTag.git
cd MiniTag
./gradlew assembleDebug
```

### APK de release assinado

1. Criar uma keystore (uma única vez — **guarde-a e às passwords em local seguro**; sem ela não é possível publicar actualizações):
   ```bash
   keytool -genkeypair -v -keystore minitag-release.jks -alias minitag -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Copiar `keystore.properties.example` para `keystore.properties` e preencher.
3. Compilar:
   ```bash
   ./gradlew assembleRelease
   ```
   O APK fica em `app/build/outputs/apk/release/MiniTag-v1.0-release.apk`.

`keystore.properties` e `*.jks` estão no `.gitignore` e nunca devem ser publicados.

## Estrutura do projecto

| Ficheiro | Função |
|---|---|
| `TagEngine.kt` | Lógica de tags pura JVM (jaudiotagger), sem dependências Android — testável fora do telemóvel |
| `TagRepository.kt` | Ponte entre o Storage Access Framework e o `TagEngine` (cópia temporária, escrita segura, MediaStore) |
| `EditDialog.kt` | Diálogo de edição para 1 ou N ficheiros |
| `MainActivity.kt` | Lista, selecção, leitura e gravação em segundo plano |
| `TrackAdapter.kt` | Linhas da lista |

## Desenvolvimento com IA

O MiniTag foi desenvolvido com recurso a ferramentas de inteligência artificial:

- **Código e documentação** — escritos com a assistência do Claude (Anthropic).
- **Ícone** — gerado com o Gemini (Google) e adaptado para ícone Android.

A app foi testada num dispositivo Android real, mas pode conter erros que passaram despercebidos. **Faça cópia de segurança dos seus ficheiros de música antes de editar muitos de uma vez.** Relatos de problemas são bem-vindos em [Issues](../../issues).

## Bibliotecas

- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) 3.0.1 — LGPL-2.1 (ver [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md))
- AndroidX e Material Components — Apache-2.0

## Histórico de versões

Ver [`CHANGELOG.md`](CHANGELOG.md).

## Licença

Distribuído sob a licença **MIT** — ver [`LICENSE`](LICENSE).

Pode usar, copiar, modificar e distribuir o código, inclusive em projectos comerciais ou fechados, desde que mantenha o aviso de copyright e a licença. O software é fornecido **"tal como está", sem qualquer garantia**, e os autores não são responsáveis por danos resultantes da sua utilização.

As bibliotecas de terceiros mantêm as suas próprias licenças (ver [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)).
