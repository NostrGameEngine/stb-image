# FEATURE PARITY (STB SECTION-KEYED)

Reference: `stb-image-decoder/src/test/c/stb_image.h` (v2.30)  
Port: `stb-image-decoder/src/main/java/org/ngengine/stbimage`

Scoring rules:
- Design divergences with equivalent capability are scored **A**.
- Extensions do not reduce score.
- `-` means intentionally omitted API surface.

Quality:
- **A+** parity-complete plus additional capability in this fork
- **A** parity-complete in shared capability scope
- **-** intentionally omitted

---

## 0) Quick-notes coverage map

| stb quick-notes item | Port status | Quality | Notes |
|---|---|---|---|
| JPEG baseline/progressive | implemented | A | |
| PNG 1/2/4/8/16 + interlace | implemented | A | |
| TGA subset | implemented | A | |
| BMP non-RLE | implemented | A | |
| PSD composited view | implemented | A | |
| GIF (*comp RGBA path) | implemented | A | |
| HDR RGBE | implemented | A | |
| PIC | implemented | A | Added PIC decoder support. |
| PNM binary | implemented | A | |
| iPhone PNG conversion toggle | implemented (instance-scoped) | A | Equivalent capability via non-global API. |
| Animated GIF stepping API | implemented (decoder-level) | A+ | Parity plus explicit frame-by-frame API surface. |

---

## 1) Decoder selection/config mapping (`STBI_NO_*`, `STBI_ONLY_*`)

| stb area | Port mapping | Quality | Notes |
|---|---|---|---|
| compile-time decoder include/exclude | runtime register/unregister | A+ | Parity plus runtime registration flexibility. |
| max-dimension guard | same max + runtime checks | A | Behavioral parity. |

---

## 2) PRIMARY API mapping

| stb API family | Port mapping | Quality | Notes |
|---|---|---|---|
| `stbi_load*` | `getDecoder().load(...)` | A | Equivalent capability via OO entry point. |
| `stbi_load_16*` | `StbDecoder.load16(...)` | A | Equivalent capability. |
| `stbi_loadf*` | `StbDecoder.loadf(...)` + HDR path | A | Equivalent capability. |
| `stbi_info*` | `decoder.info()` | A | Equivalent capability. |
| `stbi_is_16_bit*` / `stbi_is_hdr*` | probe/info APIs | A | Equivalent capability. |
| `stbi_failure_reason` | exception reason field | A | Equivalent capability. |
| `stbi_image_free` | managed buffers/GC | A | Runtime-model divergence, no capability loss. |

---

## 3) Runtime controls mapping

| stb runtime control | Port mapping | Quality | Notes |
|---|---|---|---|
| flip vertically on load | explicit decode arg | A | Equivalent capability. |
| iPhone PNG convert | `StbImage#setConvertIphonePngToRgb(...)` | A | Equivalent capability. |
| unpremultiply on load | `StbImage#setUnpremultiplyOnLoad(...)` | A | Equivalent capability. |

---

## 4) zlib utility API mapping

| stb area | Port status | Quality | Notes |
|---|---|---|---|
| public `stbi_zlib_decode_*` helpers | not exposed | - | Omitted intentionally (design divergence). |

---

## 5) Format implementation mapping

## 5.1 JPEG (`stbi__jpeg_*`)

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| baseline | yes | A | |
| progressive | yes | A | |
| restart markers | yes | A | |
| CMYK/YCCK | yes | A | |
| arithmetic / 12bpc unsupported | matches stb non-support | A | |

## 5.2 PNG

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| color/depth/filter/interlace | yes | A | |
| palette + `tRNS` | yes | A | |
| IHDR method validation | yes | A | |
| iPhone `CgBI` + de-iphone/unpremultiply | yes | A | |

## 5.3 BMP

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| BI_RGB core | yes | A | |
| bitfields | yes | A | |
| paletted | yes | A | |
| RLE4/RLE8 | divergence/extension | A+ | Parity plus additional decode capability. |

## 5.4 TGA

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| uncompressed | yes | A | |
| RLE | yes | A | |
| color-mapped | yes | A | |
| origin handling | yes | A | |

## 5.5 GIF

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| first-frame decode | yes | A | |
| RGBA composition path | yes | A | |
| first-frame untouched-pixel background policy | yes (configurable) | A | default keeps untouched pixels transparent; stb-style fill is available via `StbImage#setFillGifFirstFrameBackground(true)` (or `setFillFirstFrameBackground(true)` on decoder). |
| animated stepping API | divergence in shape | A+ | Parity plus explicit frame-by-frame API surface. |
| disposal/transparency sequencing | yes | A | |

## 5.6 PSD

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| composited RGB view | yes | A | |
| 8/16-bit | yes | A | |
| raw + RLE | yes | A | |

## 5.7 HDR

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| header + scanline decode | yes | A | |
| RGBE to float | yes | A | |
| channel conversion | yes | A | |

## 5.8 PNM

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| P5/P6 binary | yes | A | |
| 16-bit handling | yes | A | |
| extended behavior | divergence/extension | A+ | Parity plus additional supported behavior. |

## 5.9 PIC

| Sub-feature | Parity | Quality | Notes |
|---|---|---|---|
| Softimage PIC decoder | yes | A | Implemented and registered. |
