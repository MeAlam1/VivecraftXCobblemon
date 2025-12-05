package org.vivecraft.api.client.tracker.swing;

import org.vivecraft.api.client.tracker.TrackerSource;

/**
 * Minimal provider interface for swing detectors that allows registering listeners.
 */
public interface SwingSource extends TrackerSource {
    void addListener(SwingTracker tracker);

    void removeListener(SwingTracker tracker);
}
