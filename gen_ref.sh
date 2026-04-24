#!/bin/bash
# Script to generate reference pixel data from stb_image.h

set -e

 

echo "=== Generating reference data from stb_image.h ==="

# Create expected directory
mkdir -p build
mkdir -p stb-image/src/test/resources/testData/expected


 
echo "Compiling stb_image reference generator..."
gcc -O2 -Isrc/test/c -o build/stb_ref stb-image/src/test/c/stb_ref.c -lm

echo ""
echo "Processing images..."

for img in stb-image/src/test/resources/testData/image/*; do
    if [ -f "$img" ]; then
        # Get base filename without extension
        basename=$(basename "$img")
        name="${basename%.*}"
        ext="${basename##*.}"

        # Skip invalid/truncated files
        if [[ "$name" == "random" ]] || [[ "$basename" == *"truncated"* ]]; then
            echo "Skipping: $basename (invalid test file)"
            continue
        fi

        # Determine desired channels based on format
        case "$ext" in
            jpg|JPG|jpeg)
                # JPEG: request 4 channels (will give RGB or expand grayscale to RGB)
                channels=4
                ;;
            png|PNG|gif|GIF|bmp|BMP|tga|TGA|psd|PSD|hdr|HDR)
                channels=4
                ;;
            ppm|PPM|pgm|PGM|pnm|PNM)
                channels=3
                ;;
            *)
                channels=4
                ;;
        esac

        output="stb-image/src/test/resources/testData/expected/${basename}.bin"
        build/stb_ref "$img" "$output" $channels || echo "FAILED: $basename"
    fi
done

echo ""
echo "=== Done! Reference files in stb-image/src/test/resources/testData/expected/ ==="
echo "Generating index..."



find "stb-image/src/test/resources/testData/image/" -type f -print0 \
  | sort -z \
  | while IFS= read -r -d '' f; do
      realpath --relative-to="$PWD" "$f"
    done > "stb-image/src/test/resources/testData/index.txt"

echo "Done"