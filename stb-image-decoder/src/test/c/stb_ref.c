#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <input> <output> [channels]\n", argv[0]);
        return 1;
    }

    const char *input_path = argv[1];
    const char *output_path = argv[2];
    int req_channels = (argc > 3) ? atoi(argv[3]) : 4;

    int width, height, channels;
    unsigned char *data = stbi_load(input_path, &width, &height, &channels, req_channels);

    if (!data) {
        fprintf(stderr, "Failed to load: %s (requested %d channels)\n", input_path, req_channels);
        return 1;
    }

    int output_channels = channels;

    // Write binary format: width(4 bytes) + height(4 bytes) + channels(4 bytes) + pixel data
    FILE *out = fopen(output_path, "wb");
    if (!out) {
        fprintf(stderr, "Failed to open output: %s\n", output_path);
        free(data);
        return 1;
    }

    fwrite(&width, 4, 1, out);
    fwrite(&height, 4, 1, out);
    fwrite(&output_channels, 4, 1, out);
    fwrite(data, 1, width * height * output_channels, out);

    fclose(out);
    free(data);

    printf("%s -> %s (%dx%d, %d channels)\n", input_path, output_path, width, height, output_channels);
    return 0;
}