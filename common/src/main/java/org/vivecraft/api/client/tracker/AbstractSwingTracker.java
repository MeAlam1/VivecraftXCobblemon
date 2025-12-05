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
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.common.utils.MathUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure tracking component. Detects swings based on device/tip velocity and emits events.
 * No Minecraft-specific action is performed here.
 */
public class AbstractSwingTracker implements DebugRenderTracker {
    public static final int[] TRACKER_DEVICE_INDICES = new int[]{
        MCVR.MAIN_CONTROLLER, MCVR.OFFHAND_CONTROLLER, MCVR.RIGHT_FOOT_TRACKER, MCVR.LEFT_FOOT_TRACKER
    };

    public static final float BASE_SWING_SPEED_THRESHOLD = 3.0f;

    private static final VRBodyPart[] BODY_PARTS = new VRBodyPart[]{
        VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND, VRBodyPart.RIGHT_FOOT, VRBodyPart.LEFT_FOOT
    };

    private final Minecraft minecraft;
    private final ClientDataHolderVR clientData;
    private final List<SwingTracker> swingListeners = new ArrayList<>();

    private final Vec3[] lastTipPositions = new Vec3[4];
    private final Vec3[] lastDevicePositions = new Vec3[4];
    private final Quaternionf[] lastDeviceRotations = new Quaternionf[4];
    private final boolean[] isSwinging = new boolean[4];

    public AbstractSwingTracker(Minecraft minecraft, ClientDataHolderVR clientData) {
        this.minecraft = minecraft;
        this.clientData = clientData;
    }

    public void addListener(SwingTracker tracker) {
        if (!swingListeners.contains(tracker)) swingListeners.add(tracker);
    }

    public void removeListener(SwingTracker tracker) {
        swingListeners.remove(tracker);
    }


    public void tick(@Nullable LocalPlayer player) {
        if (player == null || minecraft == null || clientData == null || clientData.vrPlayer == null) return;

        int trackers = getTrackerCount();
        for (int tracker = 0; tracker < trackers; tracker++) {
            processTracker(tracker, player);
        }
    }

    private int getTrackerCount() {
        if (clientData.vrSettings != null && clientData.vrSettings.feetCollision &&
            clientData.vrPlayer.vrdata_world_pre.fbtMode != null &&
            clientData.vrPlayer.vrdata_world_pre.fbtMode != FBTMode.ARMS_ONLY)
        {
            return 4;
        }
        return 2;
    }

    private void processTracker(int tracker, LocalPlayer player) {
        int deviceIndex = TRACKER_DEVICE_INDICES[tracker];

        Vec3 handPos = getDevicePosition(deviceIndex);
        Vector3f handDir = getHandDirection(deviceIndex);
        if (handPos == null || handDir == null) return;

        Vec3 tip = computeTip(handPos, handDir, 0.3f);
        Quaternionf rot = getHandRotation(deviceIndex);
        if (rot == null) return;

        float speed = computeSpeed(tracker, tip);
        SwingContext context = new SwingContext(BODY_PARTS[tracker], handPos, tip, speed, rot);

        handleSwingState(tracker, player, context);

        lastTipPositions[tracker] = tip;
        lastDevicePositions[tracker] = handPos;
        lastDeviceRotations[tracker] = rot;
    }

    @Nullable
    private Vec3 getDevicePosition(int deviceIndex) {
        var device = clientData.vrPlayer.vrdata_world_pre.getDevice(deviceIndex);
        return device != null ? device.getPosition() : null;
    }

    @Nullable
    private Vector3f getHandDirection(int deviceIndex) {
        var hand = clientData.vrPlayer.vrdata_world_pre.getHand(deviceIndex);
        return hand != null ? hand.getCustomVector(MathUtils.BACK) : null;
    }

    @Nullable
    private Quaternionf getHandRotation(int deviceIndex) {
        var hand = clientData.vrPlayer.vrdata_world_pre.getHand(deviceIndex);
        return hand != null ? new Quaternionf().setFromNormalized(hand.getMatrix()) : null;
    }

    private Vec3 computeTip(Vec3 handPos, Vector3f handDir, float offset) {
        Vector3f tipOffsetVec = handDir.mul(offset, new Vector3f());
        return handPos.add(tipOffsetVec.x, tipOffsetVec.y, tipOffsetVec.z);
    }

    private float computeSpeed(int tracker, Vec3 tip) {
        Vec3 lastTipVec = lastTipPositions[tracker];
        if (lastTipVec == null) return 0f;
        double dist = tip.distanceTo(lastTipVec);
        return (float) (dist * 20.0);
    }

    private void handleSwingState(int tracker, LocalPlayer player, SwingContext context) {
        float startThreshold = BASE_SWING_SPEED_THRESHOLD * (player.isCreative() ? 1.5f : 1.0f) * 0.15f;

        if (!isSwinging[tracker] && context.speed() > startThreshold) {
            isSwinging[tracker] = true;
            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingStart(context);
        }

        if (isSwinging[tracker]) {
            for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingUpdate(context);

            if (context.speed() > BASE_SWING_SPEED_THRESHOLD) {
                for (SwingTracker _tracker : this.swingListeners) _tracker.onSwingImpact(context);
            }
        }

        if (isSwinging[tracker] && context.speed() <= startThreshold) {
            isSwinging[tracker] = false;
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
    public void idleProcess(@Nullable LocalPlayer player) {
    }

    @Override
    public void activeProcess(@Nullable LocalPlayer player) {
        tick(player);
    }

    @Override
    public void inactiveProcess(@Nullable LocalPlayer player) {
        for (int i = 0; i < 4; i++) {
            lastTipPositions[i] = null;
            lastDevicePositions[i] = null;
            lastDeviceRotations[i] = null;
            isSwinging[i] = false;
        }
    }


    @Override
    public void renderDebug() {
    }
}
