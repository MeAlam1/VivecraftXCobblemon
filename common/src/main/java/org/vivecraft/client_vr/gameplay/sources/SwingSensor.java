package org.vivecraft.client_vr.gameplay.sources;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.Tracker;
import org.vivecraft.api.client.tracker.TrackerSensor;
import org.vivecraft.api.client.tracker.swing.SwingContext;
import org.vivecraft.api.client.tracker.swing.SwingTracker;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.gameplay.trackers.DebugRenderTracker;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.common.utils.MathUtils;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Base implementation of a swing tracker used for VR input.
 *
 * <p>This class:
 * <ul>
 *   <li>Tracks motion for multiple VR body parts (hands, optional feet).</li>
 *   <li>Detects swing lifecycle events: start, update, impact, end.</li>
 *   <li>Notifies registered {@link SwingTracker} listeners of those events.</li>
 * </ul>
 * <p>
 * Notes:
 * <ul>
 *   <li>All spatial values are in world meters (Minecraft block units ≈ meters).</li>
 *   <li>Speed values are returned in meters per second (m/s).</li>
 *   <li>The tracker is designed to be polled once per client tick (see {@link #processType}).</li>
 * </ul>
 */
public class SwingSensor implements DebugRenderTracker, TrackerSensor {
    /**
     * Default speed threshold (m/s) used to determine an impact on a swing.
     * This value is scaled by game state (creative) and hand bias when applied.
     */
    public static final float BASE_SWING_SPEED_THRESHOLD = 3.0f;

    /**
     * Sliding window length (in ticks) used to smooth speed measurements while swinging.
     * A tick is one client tick.
     */
    private static final int SPEED_WINDOW_TICKS = 6;

    /** Reference to the Minecraft client instance. */
    private final Minecraft minecraft;

    /** Holder for VR-specific client-side data. */
    private final ClientDataHolderVR clientData;

    /** Registered listeners that will receive swing lifecycle callbacks. */
    private final List<SwingTracker> swingListeners = new ArrayList<>();

    /**
     * Internal per-body-part state used to detect swings and compute smoothed speeds.
     * <p>
     * All fields are updated on ticks and are intended to be accessed only from the client
     * tick thread that drives this tracker.
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

    /** Map storing per-body-part tracker state. Keys cover tracked parts only. */
    private final Map<VRBodyPart, TrackerState> trackerStates = new EnumMap<>(VRBodyPart.class);

    /**
     * Create a new tracker instance tied to the given Minecraft client and VR client data holder.
     *
     * @param minecraft  Minecraft client instance.
     * @param clientData Client-side VR state holder providing device poses and settings.
     */
    public SwingSensor(Minecraft minecraft, ClientDataHolderVR clientData) {
        this.minecraft = minecraft;
        this.clientData = clientData;


        trackerStates.put(VRBodyPart.MAIN_HAND, new TrackerState());
        trackerStates.put(VRBodyPart.OFF_HAND, new TrackerState());
        trackerStates.put(VRBodyPart.RIGHT_FOOT, new TrackerState());
        trackerStates.put(VRBodyPart.LEFT_FOOT, new TrackerState());
    }

    /**
     * Register a {@link SwingTracker} listener.
     *
     * @param tracker listener to notify; duplicates are ignored.
     */
    public void addListener(Tracker tracker) {
        if (!(tracker instanceof SwingTracker)) return;
        if (!swingListeners.contains(tracker)) swingListeners.add((SwingTracker) tracker);
    }

    /**
     * Unregister a {@link SwingTracker} listener.
     *
     * @param tracker listener to remove; no-op if not registered.
     */
    public void removeListener(Tracker tracker) {
        if (!(tracker instanceof SwingTracker)) return;
        swingListeners.remove(tracker);
    }

    /**
     * Called once per tick to update tracker state for the given player and emit events.
     * <p>
     * This method is a public entry-point and performs null-checks on required dependencies:
     * it returns immediately if there is no player, no client data, or VR player data is missing.
     *
     * @param player the local player for whom to process tracker data; may be null and is guarded.
     */
    public void tick(@Nullable LocalPlayer player) {
        if (player == null || minecraft == null || clientData == null || clientData.vrPlayer == null) return;


        for (VRBodyPart bodyPart : getTrackedBodyParts()) {
            processTracker(bodyPart, player);
        }
    }

    /**
     * Determine which VR body parts are tracked based on VR settings and player mode.
     * <p>
     * - If feet collision is enabled and FBT (full-body tracking) is not ARMS_ONLY, track hands + feet.
     * - Otherwise, track only hands.
     *
     * @return an immutable list of body parts to process each tick.
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
     * Core per-body-part processing pipeline:
     * <ol>
     *   <li>Obtain the current device pose for the body part.</li>
     *   <li>Compute the visual "tip" position (hand end / weapon end).</li>
     *   <li>Compute relative movement and smoothed speed.</li>
     *   <li>Construct a {@link SwingContext} and dispatch swing lifecycle handling.</li>
     *   <li>Update stored previous-frame state for the next tick.</li>
     * </ol>
     *
     * @param bodyPart which body part to process
     * @param player   the local player (guaranteed non-null by caller)
     */
    private void processTracker(VRBodyPart bodyPart, LocalPlayer player) {
        var devicePose = clientData.vrPlayer.vrdata_world_pre.getBodyPart(bodyPart);
        if (devicePose == null) return;

        Vec3 handPos = devicePose.getPosition();
        Vector3f handDir = devicePose.getCustomVector(MathUtils.BACK);
        if (handPos == null || handDir == null) return;


        Vec3 tip = computeTip(handPos, handDir);
        Quaternionf rot = new Quaternionf().setFromNormalized(devicePose.getMatrix());

        float speed = computeSpeed(bodyPart, tip, handPos);

        TrackerState state = trackerStates.get(bodyPart);
        if (state != null) state.lastSpeed = speed;

        SwingContext context = new SwingContext(bodyPart, handPos, tip, speed, rot);


        handleSwingState(bodyPart, player, context);


        if (state != null) {
            state.lastTipPosition = tip;
            state.lastDevicePosition = handPos;
            state.lastDeviceRotation = rot;
        }
    }

    /**
     * Compute the "tip" position for a body part's device.
     * <p>
     * The tip is computed by moving the device position along the provided direction vector
     * by a fixed offset (0.3m). This represents the approximate end of a held item/weapon.
     *
     * @param handPos world position of the device/controller
     * @param handDir forward-facing direction vector of the device (unit-like)
     * @return computed tip world position
     */
    private Vec3 computeTip(Vec3 handPos, Vector3f handDir) {
        Vector3f tipOffsetVec = handDir.mul((float) 0.3, new Vector3f());
        return handPos.add(tipOffsetVec.x, tipOffsetVec.y, tipOffsetVec.z);
    }

    /**
     * Compute current tip speed (m/s) for the given body part.
     * <p>
     * Behavior:
     * <ul>
     *   <li>If no previous tip sample exists, returns 0.</li>
     *   <li>While currently swinging, returns a smoothed speed based on the last N per-tick displacements.</li>
     *   <li>While not swinging, returns the instantaneous per-tick speed (converted to m/s by *20).</li>
     * </ul>
     *
     * @param bodyPart  the tracked body part
     * @param tip       current tip world position
     * @param devicePos current device/controller world position
     * @return estimated tip speed in meters per second
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
     * Update swing lifecycle for the body part using the computed {@link SwingContext}.
     * <p>
     * Lifecycle:
     * <ul>
     *   <li>Start: when speed rises above a start threshold.</li>
     *   <li>Update: every tick while swinging.</li>
     *   <li>Impact: when speed exceeds a fixed base threshold; may be suppressed by higher-priority swings.</li>
     *   <li>End: when speed falls back below the start threshold.</li>
     * </ul>
     *
     * @param bodyPart the tracked body part
     * @param player   the local player (used for creative-mode scaling)
     * @param context  current computed swing context for this tick
     */
    private void handleSwingState(VRBodyPart bodyPart, LocalPlayer player, SwingContext context) {
        TrackerState state = trackerStates.get(bodyPart);
        if (state == null) return;


        float baseFactor = player.isCreative() ? 1.5f : 1.0f;
        float offhandBias = (bodyPart == VRBodyPart.OFF_HAND) ? 1.4f : 1.0f;


        float startThreshold = BASE_SWING_SPEED_THRESHOLD * baseFactor * 0.6f * offhandBias;


        if (!state.isSwinging && context.speed() > startThreshold) {
            state.isSwinging = true;


            state.cumulativeTipDelta = context.speed() / 20.0;
            state.tipSamples = 1;

            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingStart(context);
        }


        if (state.isSwinging) {
            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingUpdate(context);


            if (context.speed() > BASE_SWING_SPEED_THRESHOLD) {

                for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingImpact(context);
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
     * The tracker expects to be processed once per tick.
     *
     * @return the process type for this tracker (PER_TICK)
     */
    @Override
    public ProcessType processType() {
        return ProcessType.PER_TICK;
    }

    /**
     * Determine whether the tracker should be active for the given player context.
     * <p>
     * The tracker is inactive when:
     * <ul>
     *   <li>player/canvas/VR data are missing</li>
     *   <li>the player is dead or sleeping</li>
     *   <li>a GUI screen is open</li>
     *   <li>weapon collision is disabled by settings</li>
     *   <li>seated mode is enabled</li>
     *   <li>the client's jump tracker indicates the player is jumping</li>
     * </ul>
     *
     * @param player player being checked; may be null
     * @return true if the tracker should run in active mode, false otherwise
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
     * Active processing entry point invoked by the debug tracker framework when the tracker is active.
     *
     * @param player the local player (may be null only when {@link #isActive} would have returned false)
     */
    @Override
    public void activeProcess(@Nullable LocalPlayer player) {
        tick(player);
    }

    /**
     * Inactive processing entry point invoked by the debug tracker framework when the tracker is inactive.
     * <p>
     * This method clears per-body-part transient state so that stale data does not cause false swing detections
     * when the tracker next becomes active.
     *
     * @param player the local player
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
     * Render debug visualization for the tracker.
     */
    @Override
    public void renderDebug() {
    }
}
