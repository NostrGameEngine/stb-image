# StbImage for Java (NGE port)

**This is not a binding or wrapper: this repository contains an experimental, pure-Java (Java 11+) port/fork of `stb_image.h`**

This project is best suited for:

* sandboxed environments (e.g., restricted runtimes)
* platforms without native/JNI support
* projects that prefer "pure Java" dependencies for cross-platform compatibility, ease of use or peace of mind
* projects that load untrusted files and want to avoid native attack surfaces

> [!NOTE]
> The initial codebase was produced with AI-assisted translation, then reviewed, patched and improved by humans.

## Project status and reliability

The implementation is:

- memory-safe 
- no global state: so it is thread-safe as long as the decoder instances are not shared between threads
- hardened against OOM and DoS attack vectors
- and it intentionally does not perform any filesystem access

In practice, it should be safe to use even to load untrusted files.


> [!WARNING]
> This is still an **experimental** project. For maximum stability, performance, and long-term compatibility, using the [LWJGL3 native bindings](https://www.lwjgl.org/) to the original C library is still recommended when possible.

Two machine generated parity reports are included: 
- [FEATURE_PARITY.md](FEATURE_PARITY.md) 
- [FEATURE_PARITY_STB_SECTIONS.md](FEATURE_PARITY_STB_SECTIONS.md)



## API notes and divergences

This is not a strict 1:1 port. Some APIs were adapted to be more Java-idiomatic and to integrate better with game engines (the original target of this port). In a few places, convenience was traded for consistency and safer public APIs.

The library uses `ByteBuffer.allocate` by default, but any allocator can be wired through the StbImage constructor.

> [!IMPORTANT]
> The allocator must internally manage buffer lifecycle and release resources appropriately (for example, ensure direct buffers are reclaimed).

 


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

> [!NOTE]
> The Java package namespace was intentionally changed to `org.ngengine.stbimage`, in order to avoid collisions with other stb_image implementations.

## Unit tests and parity checks

This repo includes a [large collection of reference images](stb-image/src/test/resources/testData/image), covering a wide range of formats, features, and edge cases. The unit tests check also for pixel parity with images decoded with the [original stb_image C library (v2.30) included in this repo](stb-image/src/test/c/). 
These images have various licenses and are not suitable to be shipped in production, see [testData/README.md](stb-image/src/test/resources/testData/README.md) for details.



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

[ImageViewer.java](stb-image/src/test/java/org/ngengine/stbimage/ImageViewer.java) is a simple Swing-based image viewer that you can launch from vscode to manually test decoding of various images by dragging and dropping them onto the window.


 

## License 

[Public Domain](LICENSE) or [BSD-3-Clause](LICENSE-alt), which one you prefer.

