package com.stb.image;

/**
 * Exception thrown when image decoding fails.
 */
public class StbFailureException extends RuntimeException {
    private final String reason;
    private final boolean userMessage;

    public StbFailureException(String reason) {
        this(reason, false);
    }

    public StbFailureException(String reason, boolean userMessage) {
        super(userMessage ? reason : "Image decode failed: " + reason);
        this.reason = reason;
        this.userMessage = userMessage;
    }

    public String getReason() {
        return reason;
    }

    public boolean isUserMessage() {
        return userMessage;
    }
}
