package org.vivecraft.api.client.tracker.context;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.vivecraft.api.data.VRBodyPart;

/**
 * Simple container with everything the swing tracker needs to pass around.
 * <p>
 * <i>This is just raw data. No game logic, no interpretation.</i>
 * </p>
 *
 * @param bodyPart  which controller/foot this swing came from
 * @param start     the base position of the hand/controller in world space
 * @param tip       the end of the swing (a bit forward from the hand)
 * @param speed     how fast the tip is moving, in m/s
 * @param rotation  the current rotation of the hand/controller
 * @param direction the normalized direction vector of the swing
 */
public record SwingContext(VRBodyPart bodyPart, Vec3 start, Vec3 tip, float speed, Quaternionf rotation,
                           Vec3 direction)
{
}
