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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure tracking component. Detects swings based on device/tip velocity and emits events.
 * No Minecraft-specific action is performed here.
 */
public class AbstractSwingTracker implements DebugRenderTracker {
    public static final float BASE_SWING_SPEED_THRESHOLD = 3.0f;

    private final Minecraft minecraft;
    private final ClientDataHolderVR clientData;
    private final List<SwingTracker> swingListeners = new ArrayList<>();

    private static class TrackerState {
        Vec3 lastTipPosition;
        Vec3 lastDevicePosition;
        Quaternionf lastDeviceRotation;
        boolean isSwinging;
    }

    private final Map<VRBodyPart, TrackerState> trackerStates = new EnumMap<>(VRBodyPart.class);

    public AbstractSwingTracker(Minecraft minecraft, ClientDataHolderVR clientData) {
        this.minecraft = minecraft;
        this.clientData = clientData;

        trackerStates.put(VRBodyPart.MAIN_HAND, new TrackerState());
        trackerStates.put(VRBodyPart.OFF_HAND, new TrackerState());
        trackerStates.put(VRBodyPart.RIGHT_FOOT, new TrackerState());
        trackerStates.put(VRBodyPart.LEFT_FOOT, new TrackerState());
    }

    public void addListener(SwingTracker tracker) {
        if (!swingListeners.contains(tracker)) swingListeners.add(tracker);
    }

    public void removeListener(SwingTracker tracker) {
        swingListeners.remove(tracker);
    }

    public void tick(@Nullable LocalPlayer player) {
        if (player == null || minecraft == null || clientData == null || clientData.vrPlayer == null) return;

        for (VRBodyPart bodyPart : getTrackedBodyParts()) {
            processTracker(bodyPart, player);
        }
    }

    private List<VRBodyPart> getTrackedBodyParts() {
        if (clientData.vrSettings != null && clientData.vrSettings.feetCollision &&
            clientData.vrPlayer.vrdata_world_pre.fbtMode != null &&
            clientData.vrPlayer.vrdata_world_pre.fbtMode != FBTMode.ARMS_ONLY)
        {
            return List.of(VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND, VRBodyPart.RIGHT_FOOT, VRBodyPart.LEFT_FOOT);
        }
        return List.of(VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND);
    }

    private void processTracker(VRBodyPart bodyPart, LocalPlayer player) {
        var devicePose = clientData.vrPlayer.vrdata_world_pre.getBodyPart(bodyPart);
        if (devicePose == null) return;

        Vec3 handPos = devicePose.getPosition();
        Vector3f handDir = devicePose.getCustomVector(MathUtils.BACK);
        if (handPos == null || handDir == null) return;

        Vec3 tip = computeTip(handPos, handDir, 0.3f);
        Quaternionf rot = new Quaternionf().setFromNormalized(devicePose.getMatrix());

        float speed = computeSpeed(bodyPart, tip);
        SwingContext context = new SwingContext(bodyPart, handPos, tip, speed, rot);

        handleSwingState(bodyPart, player, context);

        TrackerState state = trackerStates.get(bodyPart);
        if (state != null) {
            state.lastTipPosition = tip;
            state.lastDevicePosition = handPos;
            state.lastDeviceRotation = rot;
        }
    }

    private Vec3 computeTip(Vec3 handPos, Vector3f handDir, float offset) {
        Vector3f tipOffsetVec = handDir.mul(offset, new Vector3f());
        return handPos.add(tipOffsetVec.x, tipOffsetVec.y, tipOffsetVec.z);
    }

    private float computeSpeed(VRBodyPart bodyPart, Vec3 tip) {
        TrackerState state = trackerStates.get(bodyPart);
        if (state == null) return 0f;
        Vec3 lastTipVec = state.lastTipPosition;
        if (lastTipVec == null) return 0f;
        double dist = tip.distanceTo(lastTipVec);
        return (float) (dist * 20.0);
    }

    private void handleSwingState(VRBodyPart bodyPart, LocalPlayer player, SwingContext context) {
        TrackerState state = trackerStates.get(bodyPart);
        if (state == null) return;

        float startThreshold = BASE_SWING_SPEED_THRESHOLD * (player.isCreative() ? 1.5f : 1.0f) * 0.15f;

        if (!state.isSwinging && context.speed() > startThreshold) {
            state.isSwinging = true;
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
            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingEnd(context);
        }
    }

    @Override
    public ProcessType processType() {
        return ProcessType.PER_TICK;
    }

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

    @Override
    public void activeProcess(@Nullable LocalPlayer player) {
        tick(player);
    }

    @Override
    public void inactiveProcess(@Nullable LocalPlayer player) {
        for (TrackerState state : trackerStates.values()) {
            state.lastTipPosition = null;
            state.lastDevicePosition = null;
            state.lastDeviceRotation = null;
            state.isSwinging = false;
        }
    }

    @Override
    public void renderDebug() {
    }
}
