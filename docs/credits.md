# Third-party assets

| Asset | Source | Licence |
| --- | --- | --- |
| `assets/ui/icons/clima/icons.json`, `assets/ui/icons/weather/*.png` | [Material Design Icons](https://pictogrammers.com/library/mdi/) (`@mdi/svg`), fetched by `scripts/build-clima-icons.mjs` and `scripts/build-weather-icons.mjs` | Apache-2.0 |
| `assets/ui/icons/clima/blower-*.png` (airflow direction) | Impulse (`haval-app-tool-multimidia`), `app/src/main/res/drawable/ic_hvac_blower_*.png` | same project |
| `assets/ui/icons/clima/recirc-in.png`, `recirc-out.png` | Impulse, inlined in `ui/components/BottomBarUI.kt` as `recycleIn` / `recycleOut` | same project |
| `assets/ui/icons/clima/seat.svg` (seat glyph) | "heated seat" by **Thuy Nguyen** from the [Noun Project](https://thenounproject.com/), supplied by the owner (`noun_heatedseat_2695898`) | Noun Project — credit the author when the icon is shown publicly |
| Weather data | [Open-Meteo](https://open-meteo.com/) — no API key; the viewer sends only a position rounded to 2 decimals (~1 km) | CC-BY 4.0 / free for non-commercial use |

The OEM Beantech APKs under `local-archive/Haval/beantech-apks` are a visual
reference only. Nothing extracted from them is shipped — see CLAUDE.md.
