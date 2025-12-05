package org.vivecraft.api.client.tracker.swing;

/**
 * Listener for swing lifecycle events.
 *
 * <p>Implementations may override any subset of the lifecycle methods. Each
 * callback receives a {@link SwingContext} containing raw, read-only data about
 * the swing (body part, start position, tip position, speed, and rotation).</p>
 */
public interface SwingTracker {
    /**
     * Called when a swing is detected and has just started.
     *
     * @param context raw swing data describing the start of the swing
     */
    default void onSwingStart(SwingContext context) {}

    /**
     * Called periodically while the swing is in progress.
     *
     * @param context raw swing data describing the current state of the swing
     */
    default void onSwingUpdate(SwingContext context) {}

    /**
     * Called when the swing impacts (for example, hits a target or reaches the
     * expected impact point).
     *
     * @param context raw swing data describing the swing at impact
     */
    default void onSwingImpact(SwingContext context) {}

    /**
     * Called when the swing finishes or is cancelled.
     *
     * @param context raw swing data describing the end of the swing
     */
    default void onSwingEnd(SwingContext context) {}
}
