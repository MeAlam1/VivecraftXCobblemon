package org.vivecraft.api.client.tracker.context;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Simple container with everything the swing tracker needs to pass around.
 * <p>
 * <i>This is just raw data. No game logic, no interpretation.</i>
 * </p>
 *
 * @param bodyPartIndex which controller/foot this swing came from
 * @param start         the base position of the hand/controller in world space
 * @param tip           the end of the swing (a bit forward from the hand)
 * @param speed         how fast the tip is moving, in m/s
 * @param rot           the current rotation of the hand/controller
 */
public record SwingContext(int bodyPartIndex, Vec3 start, Vec3 tip, float speed, Quaternionf rot) {
}
