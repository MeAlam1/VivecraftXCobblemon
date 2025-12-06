package org.vivecraft.api.client.tracker;

import org.vivecraft.api.client.Tracker;

/**
 * Minimal marker interface for all tracker data sources.
 */
public interface TrackerSensor {
    void addListener(Tracker tracker);

    void removeListener(Tracker tracker);
}
