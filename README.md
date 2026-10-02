# Impulse Home

*Português abaixo · English first*

A 3D viewer and home screen for the **Haval H6** head unit (Snapdragon SA8155,
1920x720 panel, Android 9 WebView). It renders the car, its doors, lights, wheels,
climate, power flow and trips live from the vehicle bus, and replaces the stock
launcher experience on the main display.

It is part of the [Impulse](https://github.com/bobaoapae/haval-app-tool-multimidia)
ecosystem: Impulse reads and writes the vehicle bus, and Impulse Home draws it.

> Not affiliated with or endorsed by GWM / Haval. See [THIRD_PARTY.md](THIRD_PARTY.md).

## Install

**From Impulse (recommended).** Open Impulse -> *Instalar Apps* -> **Impulse Home**.
Impulse downloads the latest signed release and offers to set it up.

**By hand.** Download `impulse-home.apk` from the
[Releases](../../releases) page, then:

```bash
adb push impulse-home.apk /data/local/tmp/viewer.apk
adb shell pm install -r -i com.autolink.installer /data/local/tmp/viewer.apk
```

`adb install` stalls on this unit, and the `-i com.autolink.installer` identity is
required: the head unit refuses a plain install of this package.

Releases are signed with one key; see [SECURITY.md](SECURITY.md) to verify it.

## Build from source

```bash
node scripts/fetch-assets.mjs        # 3D models and images (maintainers and CI)
./gradlew assembleDebug testDebugUnitTest
node scripts/run-tests.mjs
```

Requirements: JDK 21, Android SDK (platform 36), Node 22. Without the asset bundle
everything compiles and the tests run, but the app starts without the car model.
Details in [CONTRIBUTING.md](CONTRIBUTING.md).

## Repository layout

| Path | What |
|---|---|
| `index.html`, `support.js` | the viewer (React + three.js, no bundler) |
| `app/` | Android shell: WebView host, vehicle-bus bridges, trip recorder |
| `vendor/` | vendored runtime libraries |
| `scripts/` | build tooling for models and textures, device harnesses, tests |
| `assets/` | fetched, not tracked (see `assets.lock.json`) |
| `CLAUDE.md` | engineering notes, measured on the car |

## Licenses

* **Code:** [AGPL-3.0](LICENSE).
* **Assets** (3D models, images, video): [LICENSE-ASSETS](LICENSE-ASSETS). They are
  not open source; official builds may be used, the files may not be reused.
* **Third-party:** [THIRD_PARTY.md](THIRD_PARTY.md).

---

## Português

Visualizador 3D e tela inicial para a central multimídia do **Haval H6**
(Snapdragon SA8155, painel 1920x720, WebView do Android 9). Desenha o carro, as
portas, luzes, rodas, clima, fluxo de energia e viagens em tempo real a partir do
barramento do veículo.

Faz parte do ecossistema [Impulse](https://github.com/bobaoapae/haval-app-tool-multimidia):
o Impulse lê e escreve no barramento, o Impulse Home desenha.

**Instalar:** pelo Impulse (*Instalar Apps* -> **Impulse Home**), ou baixe o
`impulse-home.apk` na página de [Releases](../../releases) e use os comandos `adb`
acima. A identidade `-i com.autolink.installer` é obrigatória.

**Compilar:** os comandos estão na seção *Build from source*. Os modelos 3D e as
imagens não ficam neste repositório; sem eles o código compila e os testes rodam,
mas o app abre sem o carro.

**Licenças:** código em AGPL-3.0; modelos, imagens e vídeo seguem a
[LICENSE-ASSETS](LICENSE-ASSETS) (uso das builds oficiais, sem reaproveitar os
arquivos); componentes de terceiros em [THIRD_PARTY.md](THIRD_PARTY.md).

Sem vínculo com a GWM / Haval.
