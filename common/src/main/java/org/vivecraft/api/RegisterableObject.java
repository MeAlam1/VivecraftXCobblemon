package org.vivecraft.api;

/**
 * An object that can be registered with Vivecraft. This should not be directly implemented by API-users, but rather
 * interfaces that extend this one, such as {@link org.vivecraft.api.client.Tracker} should be instead. These can be
 * registered using {@link VRAPI#register(RegisterableObject...)}.
 */
public interface RegisterableObject {
}
