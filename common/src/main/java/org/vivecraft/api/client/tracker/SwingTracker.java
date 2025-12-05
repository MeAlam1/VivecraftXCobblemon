package org.vivecraft.api.client.tracker;

import org.vivecraft.api.client.tracker.context.SwingContext;
import org.vivecraft.api.data.VRBodyPart;

public interface SwingTracker {
    default void onSwingStart(SwingContext context) {}
    default void onSwingUpdate(SwingContext context) {}
    default void onSwingImpact(SwingContext context) {}
    default void onSwingEnd(SwingContext context) {}
}
