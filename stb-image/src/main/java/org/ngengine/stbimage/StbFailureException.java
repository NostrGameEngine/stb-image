package org.ngengine.stbimage;

/**
 * Exception thrown when image decoding fails.
 */
public class StbFailureException extends RuntimeException {
    private final String reason;
    private final boolean userMessage;

    /**
     * Creates a decoder failure with an internal reason.
     *
     * @param reason failure reason
     */
    public StbFailureException(String reason) {
        this(reason, false);
    }

    /**
     * Creates a decoder failure.
     *
     * @param reason failure reason
     * @param userMessage if true, the message is already suitable for direct user display
     */
    public StbFailureException(String reason, boolean userMessage) {
        super(userMessage ? reason : "Image decode failed: " + reason);
        this.reason = reason;
        this.userMessage = userMessage;
    }

    /**
     * Returns the raw failure reason.
     *
     * @return reason string
     */
    public String getReason() {
        return reason;
    }

    /**
     * Indicates whether the exception message is already user-facing.
     *
     * @return true when message can be shown directly to end users
     */
    public boolean isUserMessage() {
        return userMessage;
    }
}
