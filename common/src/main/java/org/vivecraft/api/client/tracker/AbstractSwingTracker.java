package org.vivecraft.api.client.tracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.tracker.context.SwingContext;
import org.vivecraft.api.data.FBTMode;
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
    public static final int[] CONTROLLER_AND_FEET = new int[]{
        MCVR.MAIN_CONTROLLER, MCVR.OFFHAND_CONTROLLER, MCVR.RIGHT_FOOT_TRACKER, MCVR.LEFT_FOOT_TRACKER
    };

    public static final float DEFAULT_SPEED_THRESHOLD = 3.0f;

    public interface Listener {
        default void onSwingStart(SwingContext context) {}

        default void onSwingUpdate(SwingContext context) {}

        default void onSwingImpact(SwingContext context) {}

        default void onSwingEnd(int bodyPartIndex) {}
    }

    private final Minecraft mc;
    private final ClientDataHolderVR dh;
    private final List<Listener> listeners = new ArrayList<>();

    private final Vec3[] lastTip = new Vec3[4];
    private final Vec3[] lastPos = new Vec3[4];
    private final Quaternionf[] lastRot = new Quaternionf[4];
    private final boolean[] active = new boolean[4];

    public AbstractSwingTracker(Minecraft mc, ClientDataHolderVR dh) {
        this.mc = mc;
        this.dh = dh;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }


    public void tick(@Nullable LocalPlayer player) {
        if (player == null || mc == null || dh == null || dh.vrPlayer == null) return;

        int trackers = 2;
        if (dh.vrSettings != null && dh.vrSettings.feetCollision &&
            dh.vrPlayer.vrdata_world_pre.fbtMode != null &&
            dh.vrPlayer.vrdata_world_pre.fbtMode != FBTMode.ARMS_ONLY)
        {
            trackers = 4;
        }

        for (int i = 0; i < trackers; i++) {
            int deviceIndex = CONTROLLER_AND_FEET[i];

            Vec3 handPos = dh.vrPlayer.vrdata_world_pre.getDevice(deviceIndex).getPosition();

            Vector3f handDir = dh.vrPlayer.vrdata_world_pre.getHand(deviceIndex)
                .getCustomVector(MathUtils.BACK);

            float tipOffset = 0.3f;
            Vector3f tipOffsetVec = handDir.mul(tipOffset, new Vector3f());
            Vec3 tip = handPos.add(tipOffsetVec.x, tipOffsetVec.y, tipOffsetVec.z);

            Quaternionf rot = new Quaternionf().setFromNormalized(
                dh.vrPlayer.vrdata_world_pre.getHand(deviceIndex).getMatrix());

            Vec3 lastTipVec = lastTip[i];
            float speed;
            if (lastTipVec == null) {
                speed = 0f;
            } else {
                double dist = tip.distanceTo(lastTipVec);
                speed = (float) (dist * 20.0);
            }

            SwingContext context = new SwingContext(i, handPos, tip, speed, rot);

            float startThreshold = DEFAULT_SPEED_THRESHOLD * (player.isCreative() ? 1.5f : 1.0f) * 0.15f;
            float impactThreshold = DEFAULT_SPEED_THRESHOLD;
            if (!active[i] && context.speed() > startThreshold) {
                active[i] = true;
                for (Listener l : listeners) l.onSwingStart(context);
            }

            if (active[i]) {
                for (Listener l : listeners) l.onSwingUpdate(context);

                if (context.speed() > impactThreshold) {
                    for (Listener l : listeners) l.onSwingImpact(context);
                }
            }

            if (active[i] && context.speed() <= startThreshold) {
                active[i] = false;
                for (Listener l : listeners) l.onSwingEnd(i);
            }

            lastTip[i] = tip;
            lastPos[i] = handPos;
            lastRot[i] = rot;
        }
    }


    @Override
    public ProcessType processType() {
        return ProcessType.PER_TICK;
    }

    @Override
    public boolean isActive(@Nullable LocalPlayer player) {
        if (player == null || mc == null || dh == null || dh.vrPlayer == null) return false;
        if (mc.gameMode == null) return false;
        if (!player.isAlive() || player.isSleeping()) return false;
        if (mc.screen != null) return false;
        if (dh.vrSettings == null) return false;
        if (dh.vrSettings.weaponCollision == VRSettings.WeaponCollision.OFF) {
            return false;
        }
        if (dh.vrSettings.weaponCollision == VRSettings.WeaponCollision.AUTO &&
            player.isCreative())
        {return false;}
        if (dh.vrSettings.seated) return false;
        return dh.jumpTracker == null || !dh.jumpTracker.isjumping();
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
            lastTip[i] = null;
            lastPos[i] = null;
            lastRot[i] = null;
            active[i] = false;
        }
    }


    @Override
    public void renderDebug() {
    }
}
