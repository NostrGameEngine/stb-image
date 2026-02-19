# StbImage for Java (NGE port)

This repository contains an experimental, pure-Java (Java 11+) port/fork of `stb_image.h`.

* This is not a binding or wrapper: the implementation is entirely Java and does not call native code (no JNI).
* The goal is to provide a `stb_image`-like decoder for environments where native bindings are not available or not desirable.

## Project status and reliability

Much of the codebase was initially produced with AI-assisted translation, then reviewed and patched by humans. The implementation is memory-safe in the usual Java sense (no unsafe memory operations), and it intentionally does not perform any filesystem access on its own. In practice, it should be safe to use as long as a safe allocator is passed to the API (the default is `ByteBuffer::allocate`, which is heap-based and GC-managed).

That said, this is still an **experimental** project. For maximum stability, performance, and long-term compatibility, using the [LWJGL3 native bindings](https://www.lwjgl.org/) to the original C library is still recommended when possible.

This project is best suited for:

* sandboxed environments (e.g., restricted runtimes)
* platforms without native/JNI support
* projects that prefer "pure Java" dependencies for cross-platform compatibility, ease of use or peace of mind

## API notes and divergences

This is not a strict 1:1 port. Some APIs were adapted to be more Java-idiomatic and to integrate better with game engines (the original target of this port). In a few places, convenience was traded for consistency and safer public APIs.

## Namespace note

The Java package namespace was intentionally changed to avoid collisions with other stb_image implementations.

Current package: `org.ngengine.stbimage`



## Basic usage

```java
StbImage stbImage = new StbImage(ByteBuffer::allocate);

try {
    StbDecoder decoder = stbImage.getDecoder(ByteBuffer.wrap(input), false);
    StbImageInfo info = decoder.info();

    StbImageResult result;
    if (info.is16Bit()) {
        result = decoder.load16(4);
    } else {
        result = decoder.load(4);
    }

    ByteBuffer data = result.getData();
} catch (Exception e) {
    System.err.println("File not supported!");
}
```

## Unit tests and parity checks

This repo includes a [large collection of reference images](stb-image-decoder/src/test/resources/testData/image), covering a wide range of formats, features, and edge cases. The unit tests check also for pixel parity with images decoded with the [original stb_image C library (v2.30) included in this repo](stb-image-decoder/src/test/c/). 
These images have various licenses and are not suitable to be shipped in production, see [testData/README.md](stb-image-decoder/src/test/resources/testData/README.md) for details.



To run tests you need to first generate the reference data with (linux):

```bash
./gen_ref.sh
```
*Note: you need gcc for this, since it will compile a small C program that uses stb_image.h to decode all test images to raw pixel data on disk*

Then run the tests with

```bash
./gradlew test --info
```

## Manual test

[ImageViewer.java](stb-image-decoder/src/test/java/org/ngengine/stbimage/ImageViewer.java) is a simple Swing-based image viewer that you can launch from vscode to manually test decoding of various images by dragging and dropping them onto the window.


## Parity Reports

Two machine generated parity reports are included: 
- [FEATURE_PARITY.md](FEATURE_PARITY.md) 
- [FEATURE_PARITY_STB_SECTIONS.md](FEATURE_PARITY_STB_SECTIONS.md)


## License 

[Public Domain](LICENSE) or [BSD-3-Clause](LICENSE-alt), which one you prefer.

