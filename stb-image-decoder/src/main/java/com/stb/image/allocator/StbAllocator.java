package com.stb.image.allocator;

import java.nio.ByteBuffer;

/**
 * Interface for custom memory allocation.
 * Implement this to integrate with custom memory allocators (e.g., jMonkeyEngine).
 * All ByteBuffers returned should be treated as potentially direct buffers.
 */
@FunctionalInterface
public interface StbAllocator {

    /**
     * Allocates a ByteBuffer of the specified size.
     *
     * @param size the number of bytes to allocate
     * @return a ByteBuffer of at least the specified size
     * @throws IllegalArgumentException if size is negative or zero
     * @throws OutOfMemoryError if allocation fails
     */
    ByteBuffer allocate(int size);

    /**
     * Default allocator using ByteBuffer.allocate().
     */
    StbAllocator DEFAULT = size -> {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        return java.nio.ByteBuffer.allocate(size);
    };

    /**
     * Creates an allocator that uses direct ByteBuffers.
     */
    StbAllocator DIRECT = size -> {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        return java.nio.ByteBuffer.allocateDirect(size);
    };
}
