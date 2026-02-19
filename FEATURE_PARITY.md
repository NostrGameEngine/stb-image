# FEATURE PARITY REPORT

## Scope and scoring policy

Reference: `stb-image-decoder/src/test/c/stb_image.h` (stb_image v2.30)  
Port: `stb-image-decoder/src/main/java/org/ngengine/stbimage`

Scoring rules:
1. Design divergences that keep the same capability are scored **A**.
2. Extensions beyond stb are divergences/extensions and do not reduce score.
3. `-` is used for intentionally omitted surface that is outside this fork's public API goals.

Quality labels:
- **A+** parity-complete plus additional capability in this fork
- **A** parity-complete in shared capability scope
- **-** intentionally omitted

---

## Core API parity matrix

| Feature area | stb_image | Java port | Quality | Notes |
|---|---|---|---|---|
| Core load entry shape | `stbi_load*` family | `StbImage.getDecoder(...).load(...)` | A | Design divergence with equivalent capability. |
| Decoder selection | compile-time/static integration | runtime register/unregister | A+ | Parity plus runtime registration flexibility. |
| Allocator control | malloc/realloc/free hooks | `IntFunction<ByteBuffer>` allocator | A | Equivalent capability in Java shape. |
| Error reporting | NULL + reason API | exceptions (`StbFailureException`) | A | Equivalent capability. |
| Dimension guards | `STBI_MAX_DIMENSIONS` + checks | same + `validateDimensions` | A | Behavioral parity. |
| Vertical flip control | global/thread-local toggle | explicit decode argument | A | Equivalent capability. |
| `is_16_bit` / `is_hdr` discoverability | top-level API family | decoder/probe/info APIs | A | Equivalent capability. |
| Public zlib helper API | `stbi_zlib_decode_*` | not exposed | - | Omitted intentionally (design divergence). |
| iPhone PNG conversion control | global/thread-local toggle | instance-scoped toggle | A | Equivalent capability. |
| Unpremultiply-on-load control | global/thread-local toggle | instance-scoped toggle | A | Equivalent capability. |

---

## Decoder/format parity matrix

| Format | Sub-feature | stb_image | Java port | Quality | Notes |
|---|---|---|---|---|---|
| JPEG | baseline/progressive/restart/CMYK-YCCK | yes | yes | A | Unsupported modes (arith/12bpc) match stb non-support. |
| PNG | core color/depth/filter/interlace + `tRNS` | yes | yes | A | Includes strict IHDR method checks, matching stb behavior. |
| PNG | iPhone `CgBI` path | yes | yes | A | Raw-deflate + de-iphone + optional unpremultiply implemented. |
| BMP | BI_RGB/bitfields/paletted | yes | yes | A | |
| BMP | RLE4/RLE8 | no (rejected in stb path) | yes | A+ | Parity plus additional decode capability. |
| GIF | first-frame decode + RGBA composition | yes | yes | A | |
| GIF | first-frame untouched-pixel background policy | stb-style background fill default | transparent default (configurable) | A | defaults to modern viewer-friendly transparency; stb behavior available via `StbImage#setFillGifFirstFrameBackground(true)` (or decoder-level override). |
| GIF | animated frame stepping | internal in stb | decoder API `loadNextFrame` | A+ | Parity plus explicit frame-by-frame API surface. |
| TGA | uncompressed/RLE/mapped/origin | yes | yes | A | |
| PSD | composited RGB 8/16-bit + raw/RLE | yes | yes | A | |
| HDR (RGBE) | header + decode + float path | yes | yes | A | |
| PNM | P5/P6 + 16-bit | yes | yes | A | |
| PNM | extended behavior | limited | extended | A+ | Parity plus additional supported behavior. |
| PIC | Softimage PIC | yes | yes | A | Added decoder and registry wiring. |

---

## Test coverage and evidence

Primary test suites:
- `stb-image-decoder/src/test/java/org/ngengine/stbimage/StbImagePixelTest.java`
- `stb-image-decoder/src/test/java/org/ngengine/stbimage/StbImageApiTest.java`
- `stb-image-decoder/src/test/java/org/ngengine/stbimage/GifAnimationTest.java`
