package org.vivecraft.api.client.tracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.tracker.context.SwingContext;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.gameplay.trackers.DebugRenderTracker;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.common.utils.MathUtils;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Base implementation for VR swing-tracking logic.
 * Tracks motion of multiple VR body parts, detects swing start/update/impact/end,
 * and forwards events to registered {@link SwingTracker} listeners.
 */
public class AbstractSwingTracker implements DebugRenderTracker {
    /**
     * Base speed threshold (in m/s) for detecting swing impacts.
     */
    public static final float BASE_SWING_SPEED_THRESHOLD = 3.0f;

    /**
     * Number of ticks used to calculate whether the tracker is swinging or not.
     */
    private static final int SPEED_WINDOW_TICKS = 6;

    /**
     * Minecraft instance for accessing the client.
     */
    private final Minecraft minecraft;

    /**
     * Client VR data holder for accessing VR player data.
     */
    private final ClientDataHolderVR clientData;

    /**
     * Registered swing listeners to notify of swing events.
     */
    private final List<SwingTracker> swingListeners = new ArrayList<>();

    /**
     * Internal state for each tracked VR body part.
     */
    private static class TrackerState {
        Vec3 lastTipPosition;
        Vec3 lastDevicePosition;
        Quaternionf lastDeviceRotation;
        boolean isSwinging;

        double cumulativeTipDelta;
        int tipSamples;


        float lastSpeed;


        final double[] recentDists;
        int recentIndex;
        int recentCount;

        TrackerState() {
            this.recentDists = new double[SPEED_WINDOW_TICKS];
            this.recentIndex = 0;
            this.recentCount = 0;
            this.cumulativeTipDelta = 0.0;
            this.tipSamples = 0;
            this.lastSpeed = 0f;
        }
    }

    /**
     * Tracker states for each VR body part being monitored.
     */
    private final Map<VRBodyPart, TrackerState> trackerStates = new EnumMap<>(VRBodyPart.class);

    /**
     * Constructor for AbstractSwingTracker.
     * Initializes the tracker with the given Minecraft instance and client VR data holder.
     *
     * @param minecraft  the Minecraft instance
     * @param clientData the client VR data holder
     */
    public AbstractSwingTracker(Minecraft minecraft, ClientDataHolderVR clientData) {
        this.minecraft = minecraft;
        this.clientData = clientData;

        trackerStates.put(VRBodyPart.MAIN_HAND, new TrackerState());
        trackerStates.put(VRBodyPart.OFF_HAND, new TrackerState());
        trackerStates.put(VRBodyPart.RIGHT_FOOT, new TrackerState());
        trackerStates.put(VRBodyPart.LEFT_FOOT, new TrackerState());
    }

    /**
     * Adds a swing listener to receive swing events.
     * @param tracker the swing tracker to add
     */
    public void addListener(SwingTracker tracker) {
        if (!swingListeners.contains(tracker)) swingListeners.add(tracker);
    }

    /**
     * Removes a swing listener.
     * @param tracker the swing tracker to remove
     */
    public void removeListener(SwingTracker tracker) {
        swingListeners.remove(tracker);
    }

    /**
     * Processes a tick for the given player, updating swing states and notifying listeners.
     * @param player the local player
     */
    public void tick(@Nullable LocalPlayer player) {
        if (player == null || minecraft == null || clientData == null || clientData.vrPlayer == null) return;

        for (VRBodyPart bodyPart : getTrackedBodyParts()) {
            processTracker(bodyPart, player);
        }
    }

    /**
     * Determines which VR body parts should be tracked based on the current VR settings.
     * @return a list of VR body parts to track
     */
    private List<VRBodyPart> getTrackedBodyParts() {
        if (clientData.vrSettings != null && clientData.vrSettings.feetCollision &&
            clientData.vrPlayer.vrdata_world_pre.fbtMode != null &&
            clientData.vrPlayer.vrdata_world_pre.fbtMode != FBTMode.ARMS_ONLY)
        {
            return List.of(VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND, VRBodyPart.RIGHT_FOOT, VRBodyPart.LEFT_FOOT);
        }
        return List.of(VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND);
    }

    /**
     * Processes the tracker data for a specific VR body part and updates swing state.
     * @param bodyPart the VR body part to process
     * @param player   the local player
     */
    private void processTracker(VRBodyPart bodyPart, LocalPlayer player) {
        var devicePose = clientData.vrPlayer.vrdata_world_pre.getBodyPart(bodyPart);
        if (devicePose == null) return;

        Vec3 handPos = devicePose.getPosition();
        Vector3f handDir = devicePose.getCustomVector(MathUtils.BACK);
        if (handPos == null || handDir == null) return;

        Vec3 tip = computeTip(handPos, handDir);
        Quaternionf rot = new Quaternionf().setFromNormalized(devicePose.getMatrix());


        Vec3 relativeDelta = computeRelativeDelta(bodyPart, tip, handPos);
        float speed = computeSpeed(bodyPart, tip, handPos);


        TrackerState state = trackerStates.get(bodyPart);
        if (state != null) state.lastSpeed = speed;

        Vec3 direction =
            (relativeDelta != null && relativeDelta.length() > 0.0001) ? relativeDelta.normalize() : new Vec3(0, 0, 0);

        SwingContext context = new SwingContext(bodyPart, handPos, tip, speed, rot, direction);

        handleSwingState(bodyPart, player, context);

        if (state != null) {
            state.lastTipPosition = tip;
            state.lastDevicePosition = handPos;
            state.lastDeviceRotation = rot;
        }
    }

    /**
     * Computes the tip position of the swing based on hand position and direction.
     * @param handPos the position of the hand/controller
     * @param handDir the forward direction vector of the hand/controller
     * @return the computed tip position
     */
    private Vec3 computeTip(Vec3 handPos, Vector3f handDir) {
        Vector3f tipOffsetVec = handDir.mul((float) 0.3, new Vector3f());
        return handPos.add(tipOffsetVec.x, tipOffsetVec.y, tipOffsetVec.z);
    }

    /**
     * Computes the relative delta movement of the tip, accounting for device movement.
     * @param bodyPart the VR body part being tracked
     * @param tip     the current tip position
     * @param devicePos the current device position
     * @return the relative delta vector
     */
    private Vec3 computeRelativeDelta(VRBodyPart bodyPart, Vec3 tip, Vec3 devicePos) {
        TrackerState state = trackerStates.get(bodyPart);
        if (state == null) return null;
        Vec3 lastTipVec = state.lastTipPosition;
        Vec3 lastDevice = state.lastDevicePosition;
        if (lastTipVec == null) return null;

        Vec3 tipDelta = tip.subtract(lastTipVec);
        Vec3 deviceDelta = (lastDevice != null) ? devicePos.subtract(lastDevice) : new Vec3(0, 0, 0);

        return tipDelta.subtract(deviceDelta);
    }

    /**
     * Computes the speed of the tip relative to the device movement.
     * @param bodyPart the VR body part being tracked
     * @param tip    the current tip position
     * @param devicePos the current device position
     * @return the computed speed in m/s
     */
    private float computeSpeed(VRBodyPart bodyPart, Vec3 tip, Vec3 devicePos) {
        TrackerState state = trackerStates.get(bodyPart);
        if (state == null) return 0f;
        Vec3 lastTipVec = state.lastTipPosition;
        if (lastTipVec == null) return 0f;

        Vec3 tipDelta = tip.subtract(lastTipVec);
        Vec3 deviceDelta =
            (state.lastDevicePosition != null) ? devicePos.subtract(state.lastDevicePosition) : new Vec3(0, 0, 0);
        Vec3 relativeDelta = tipDelta.subtract(deviceDelta);

        double dist = relativeDelta.length();


        state.recentDists[state.recentIndex] = dist;
        state.recentIndex = (state.recentIndex + 1) % SPEED_WINDOW_TICKS;
        if (state.recentCount < SPEED_WINDOW_TICKS) state.recentCount++;

        if (state.isSwinging) {

            double sum = 0.0;
            for (int i = 0; i < state.recentCount; i++) sum += state.recentDists[i];
            double avgDist = (state.recentCount > 0) ? (sum / state.recentCount) : 0.0;
            return (float) (avgDist * 20.0);
        } else {

            return (float) (dist * 20.0);
        }
    }

    /**
     * Handles the swing state transitions and notifies listeners of swing events.
     * @param bodyPart the VR body part being tracked
     * @param player   the local player
     * @param context the current swing context
     */
    private void handleSwingState(VRBodyPart bodyPart, LocalPlayer player, SwingContext context) {
        TrackerState state = trackerStates.get(bodyPart);
        if (state == null) return;


        float baseFactor = player.isCreative() ? 1.5f : 1.0f;

        float offhandBias = (bodyPart == VRBodyPart.OFF_HAND) ? 1.4f : 1.0f;

        float startThreshold = BASE_SWING_SPEED_THRESHOLD * baseFactor * 0.6f * offhandBias;

        if (!state.isSwinging && context.speed() > startThreshold) {
            state.isSwinging = true;
            VRSettings.LOGGER.info("Swing started for {}", bodyPart);


            state.cumulativeTipDelta = context.speed() / 20.0;
            state.tipSamples = 1;

            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingStart(context);
        }

        if (state.isSwinging) {
            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingUpdate(context);

            if (context.speed() > BASE_SWING_SPEED_THRESHOLD) {

                if (!shouldSuppressImpact(bodyPart, context)) {

                    VRSettings.LOGGER.info("{} vec={}", bodyPart, context.direction());

                    for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingImpact(context);
                } else {
                    VRSettings.LOGGER.debug("Suppressed impact for {} (speed {}) due to higher-priority swing",
                        bodyPart, context.speed());
                }
            }
        }

        if (state.isSwinging && context.speed() <= startThreshold) {
            state.isSwinging = false;
            state.cumulativeTipDelta = 0.0;
            state.tipSamples = 0;
            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingEnd(context);
        }
    }


    /**
     * Determines whether to suppress the impact event for the given body part
     * @param bodyPart the VR body part being tracked
     * @param context the current swing context
     * @return true if the impact should be suppressed, false otherwise
     */
    private boolean shouldSuppressImpact(VRBodyPart bodyPart, SwingContext context) {

        if (bodyPart == VRBodyPart.MAIN_HAND) return false;

        float mySpeed = context.speed();


        for (Map.Entry<VRBodyPart, TrackerState> e : trackerStates.entrySet()) {
            VRBodyPart otherPart = e.getKey();
            TrackerState otherState = e.getValue();
            if (otherPart == bodyPart || otherState == null) continue;
            if (!otherState.isSwinging) continue;

            float otherSpeed = otherState.lastSpeed;


            if (otherPart == VRBodyPart.MAIN_HAND && otherSpeed >= mySpeed * 0.8f) {
                return true;
            }


            if (otherSpeed > mySpeed + 0.6f) {
                return true;
            }
        }


        return false;
    }

    /**
     * Gets the process type for this tracker.
     * @return the process type
     */
    @Override
    public ProcessType processType() {
        return ProcessType.PER_TICK;
    }

    /**
     * Determines if the tracker is active for the given player.
     * @param player Player being checked if they are active for this tracker instance. Will be {@code null} when not in a world.
     * @return true if the tracker is active, false otherwise
     */
    @Override
    public boolean isActive(@Nullable LocalPlayer player) {
        if (player == null || minecraft == null || clientData == null || clientData.vrPlayer == null) return false;
        if (minecraft.gameMode == null) return false;
        if (!player.isAlive() || player.isSleeping()) return false;
        if (minecraft.screen != null) return false;
        if (clientData.vrSettings == null) return false;
        if (clientData.vrSettings.weaponCollision == VRSettings.WeaponCollision.OFF) {
            return false;
        }
        if (clientData.vrSettings.weaponCollision == VRSettings.WeaponCollision.AUTO &&
            player.isCreative())
        {return false;}
        if (clientData.vrSettings.seated) return false;
        return clientData.jumpTracker == null || !clientData.jumpTracker.isjumping();
    }

    /**
     * Processes the tracker when it is active.
     * @param player Player to run this tracker for, which is the local player. Will be {@code null} when not in a world. Only {@code null} if {@link #isActive(LocalPlayer)} also got {@code null}.
     */
    @Override
    public void activeProcess(@Nullable LocalPlayer player) {
        tick(player);
    }

    /**
     * Processes the tracker when it is inactive.
     * @param player The local player. Will be {@code null} when not in a world. Only {@code null} if {@link #isActive(LocalPlayer)} also got {@code null}.
     */
    @Override
    public void inactiveProcess(@Nullable LocalPlayer player) {
        for (TrackerState state : trackerStates.values()) {
            state.lastTipPosition = null;
            state.lastDevicePosition = null;
            state.lastDeviceRotation = null;
            state.isSwinging = false;

            state.cumulativeTipDelta = 0.0;
            state.tipSamples = 0;
            state.lastSpeed = 0f;


            state.recentIndex = 0;
            state.recentCount = 0;
            Arrays.fill(state.recentDists, 0.0);
        }
    }

    /**
     * Renders debug information for the tracker.
     */
    @Override
    public void renderDebug() {
    }
}
