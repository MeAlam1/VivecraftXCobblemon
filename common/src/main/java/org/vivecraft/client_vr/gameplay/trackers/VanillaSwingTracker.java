package org.vivecraft.client_vr.gameplay.trackers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.vivecraft.api.client.Tracker;
import org.vivecraft.api.client.tracker.AbstractSwingTracker;
import org.vivecraft.api.client.tracker.SwingTracker;
import org.vivecraft.api.client.tracker.context.SwingContext;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.utils.VRItemUtils;
import org.vivecraft.client.network.ClientNetworking;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.settings.VRSettings;
import org.vivecraft.common.utils.MathUtils;
import org.vivecraft.data.ViveBlockTags;
import org.vivecraft.data.ViveItemTags;
import org.vivecraft.mod_compat_vr.epicfight.EpicFightHelper;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Vanilla adapter: listens to GeneralSwingTracker and applies vanilla behaviour.
 * Now implements Tracker so it can be registered and will attach/detach to the detector at runtime.
 */
public class VanillaSwingTracker implements SwingTracker, DebugRenderTracker {
    private static final int[] CONTROLLER_AND_FEET = AbstractSwingTracker.TRACKER_DEVICE_INDICES;
    private static final VRBodyPart[] BODYPARTS = new VRBodyPart[]{
        VRBodyPart.MAIN_HAND, VRBodyPart.OFF_HAND, VRBodyPart.RIGHT_FOOT, VRBodyPart.LEFT_FOOT
    };
    private static final float SPEED_THRESH = 3.0f;

    private final Minecraft mc;
    private final ClientDataHolderVR dh;
    private AbstractSwingTracker general;
    private final boolean[] lastWeaponSolid = new boolean[4];
    private final List<Entity>[] lastHitEntities = new List[]{
        Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()
    };
    private final Vec3[] lastBlockHit = new Vec3[4];
    private final AABB[] lastAttackAABB = new AABB[4];

    private int disableSwing = 3;

    public VanillaSwingTracker(Minecraft mc, ClientDataHolderVR dh) {
        this.mc = mc;
        this.dh = dh;
    }


    public void attach(AbstractSwingTracker general) {
        if (this.general == general) return;
        if (this.general != null) this.general.removeListener(this);
        this.general = general;
        if (this.general != null) this.general.addListener(this);
    }


    public void detach() {
        if (this.general != null) {
            this.general.removeListener(this);
            this.general = null;
        }
    }

    @Override
    public void onSwingStart(SwingContext context) {
    }

    @Override
    public void onSwingUpdate(SwingContext context) {
    }

    @Override
    public void onSwingEnd(SwingContext context) {
        int i = -1;
        for (int idx = 0; idx < BODYPARTS.length; idx++) {
            if (BODYPARTS[idx] == context.bodyPart()) {
                i = idx;
                break;
            }
        }
        if (i >= 0 && i < lastWeaponSolid.length) {
            lastWeaponSolid[i] = false;
            lastHitEntities[i] = Collections.emptyList();
        }
    }

    @Override
    public void onSwingImpact(SwingContext context) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        VRBodyPart bodyPart = context.bodyPart();
        int i = -1;
        for (int idx = 0; idx < BODYPARTS.length; idx++) {
            if (BODYPARTS[idx] == bodyPart) {
                i = idx;
                break;
            }
        }
        if (i < 0) return;

        int device = CONTROLLER_AND_FEET[i];
        boolean isHand = i < 2;

        float speedThreshold = SPEED_THRESH;
        if (player.isCreative()) speedThreshold *= 1.5f;

        ItemStack itemstack = player.getItemInHand(
            device == MCVR.OFFHAND_CONTROLLER ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        Item item = itemstack.getItem();

        boolean isTool = false;
        boolean isSword = false;
        if (!(item instanceof SwordItem) && !itemstack.is(ViveItemTags.VIVECRAFT_SWORDS) &&
            !(item instanceof TridentItem) && !itemstack.is(ViveItemTags.VIVECRAFT_SPEARS))
        {
            if (VRItemUtils.isTool(itemstack)) isTool = true;
        } else {
            isSword = true;
            isTool = true;
        }

        float weaponLength = 0.0f;
        float entityReachAdd = 0.3f;
        if (isHand) {
            double playerEntityReach = player.entityInteractionRange();
            playerEntityReach = Math.min(playerEntityReach, 6.0) - 0.5;
            if (isSword) {
                weaponLength = 0.6f;
                entityReachAdd = (float) playerEntityReach - weaponLength;
            } else if (isTool) {
                weaponLength = 0.35f;
                entityReachAdd = (float) playerEntityReach * 0.62f - weaponLength;
            } else if (!itemstack.isEmpty()) {
                weaponLength = 0.1f;
                entityReachAdd = (float) playerEntityReach * 0.16f - weaponLength;
            }
        }
        weaponLength *= dh.vrPlayer.vrdata_world_pre.worldScale;

        Vec3 handPos = context.start();
        Vec3 attackingPoint = constrain(handPos, context.tip());
        Vec3 weaponTip = constrain(handPos, handPos.add(
            dh.vrPlayer.vrdata_world_pre.getHand(device).getCustomVector(MathUtils.BACK)
                .mul(weaponLength + entityReachAdd, new Vector3f()).x,
            dh.vrPlayer.vrdata_world_pre.getHand(device).getCustomVector(MathUtils.BACK)
                .mul(weaponLength + entityReachAdd, new Vector3f()).y,
            dh.vrPlayer.vrdata_world_pre.getHand(device).getCustomVector(MathUtils.BACK)
                .mul(weaponLength + entityReachAdd, new Vector3f()).z
        ));

        AABB weaponBB = new AABB(handPos, attackingPoint);
        AABB weaponTipBB = new AABB(handPos, weaponTip);
        this.lastAttackAABB[i] = weaponTipBB;

        List<Entity> mobs = mc.level.getEntities(mc.player, weaponTipBB);
        if (dh.vrSettings.reducedPlayerReach) {
            mobs.removeIf(e -> e instanceof Player);
            List<Entity> players = mc.level.getEntities(mc.player, weaponBB);
            players.removeIf(e -> !(e instanceof Player));
            mobs.addAll(players);
        }

        boolean inAnEntity = false;
        boolean entityAct = context.speed() > speedThreshold && !this.lastWeaponSolid[i];

        BlockHitResult corner = mc.level.clip(new ClipContext(dh.vrPlayer.vrdata_world_pre.hmd.getPosition(), handPos,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (corner.getType() != HitResult.Type.MISS) {
            entityAct = false;
        }

        for (Entity entity : mobs) {
            if (entity.isPickable() && entity != mc.getCameraEntity().getVehicle() &&
                !this.lastHitEntities[i].contains(entity))
            {
                if (entityAct) {
                    if (!EpicFightHelper.isLoaded() || !EpicFightHelper.attack()) {
                        ClientNetworking.sendActiveBodyPart(BODYPARTS[i], true);
                        mc.gameMode.attack(player, entity);
                    } else {
                        entityAct = false;
                    }
                    dh.vr.triggerHapticPulse(device, 1000);
                    this.lastWeaponSolid[i] = true;
                }
                inAnEntity = true;
            }
        }

        if (context.speed() > speedThreshold) {
            this.lastHitEntities[i] = mobs;
        } else {
            this.lastHitEntities[i] = Collections.emptyList();
        }

        if (inAnEntity) {
            ClientNetworking.resetActiveBodyPart();
            return;
        }

        BlockHitResult blockHit = mc.level.clip(
            new ClipContext(handPos, context.tip(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (blockHit == null || blockHit.getType() != HitResult.Type.BLOCK) {
            ClientNetworking.resetActiveBodyPart();
            return;
        }

        BlockState blockstate = mc.level.getBlockState(blockHit.getBlockPos());
        ClientNetworking.BODY_PART_CLIENT_OVERRIDE = BODYPARTS[i];
        boolean mineableByItem = dh.vrSettings.swordBlockCollision &&
            (itemstack.isCorrectToolForDrops(blockstate) ||
                blockstate.getDestroyProgress(player, player.level(), blockHit.getBlockPos()) == 1.0F
            );
        ClientNetworking.BODY_PART_CLIENT_OVERRIDE = null;

        boolean protectedBlock = dh.vrSettings.realisticClimbEnabled &&
            (blockstate.getBlock() instanceof LadderBlock ||
                blockstate.getBlock() instanceof VineBlock ||
                blockstate.is(ViveBlockTags.VIVECRAFT_CLIMBABLE)
            );

        if ((isSword && !mineableByItem) || protectedBlock) {
            this.lastWeaponSolid[i] = false;
            ClientNetworking.resetActiveBodyPart();
            return;
        }

        if (blockHit.isInside()) {
            this.lastWeaponSolid[i] = false;
            ClientNetworking.resetActiveBodyPart();
            return;
        }

        this.lastWeaponSolid[i] = true;
        this.lastBlockHit[i] = blockHit.getLocation();
        int totalHits = 3 + Math.max(0, Math.min((int) (context.speed() - speedThreshold), 4));

        if (dh.vrSettings.doorHitting && isOpenable(blockstate, blockHit.getDirection()) &&
            mc.gameMode.useItemOn(player,
                device == MCVR.OFFHAND_CONTROLLER ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, blockHit) !=
                InteractionResult.PASS)
        {
        } else if (isHand && (item instanceof HoeItem || itemstack.is(ViveItemTags.VIVECRAFT_HOES) ||
            itemstack.is(ViveItemTags.VIVECRAFT_SCYTHES)
        )
            && (blockstate.getBlock() instanceof CropBlock || blockstate.getBlock() instanceof StemBlock ||
            blockstate.getBlock() instanceof AttachedStemBlock || blockstate.is(ViveBlockTags.VIVECRAFT_CROPS) ||
            item.useOn(
                    new UseOnContext(player, device == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, blockHit))
                .shouldSwing()
        ))
        {
            boolean useSuccessful = mc.gameMode.useItemOn(player,
                device == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, blockHit).shouldSwing();
            if (itemstack.is(ViveItemTags.VIVECRAFT_SCYTHES) && !useSuccessful) {
                mc.gameMode.useItem(player, device == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            }
        } else if (isHand && (item instanceof BrushItem)) {
            ((BrushItem) item).spawnDustParticles(player.level(), blockHit, blockstate, player.getViewVector(0.0F),
                device == 0 ? player.getMainArm() : player.getMainArm().getOpposite());
            player.level().playSound(player, blockHit.getBlockPos(),
                blockstate.getBlock() instanceof BrushableBlock ?
                    ((BrushableBlock) blockstate.getBlock()).getBrushSound() :
                    SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS);
            mc.gameMode.useItemOn(player, device == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, blockHit);
        } else if (blockstate.getBlock() instanceof NoteBlock || blockstate.is(ViveBlockTags.VIVECRAFT_MUSIC_BLOCKS)) {
            mc.gameMode.continueDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
        } else {
            ClientNetworking.sendActiveBodyPart(BODYPARTS[i], true);
            mc.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());

            if (getIsHittingBlock()) {
                for (int hit = 0; hit < totalHits; hit++) {
                    if (mc.gameMode.continueDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection())) {
                        mc.particleEngine.crack(blockHit.getBlockPos(), blockHit.getDirection());
                    }
                    clearBlockHitDelay();
                    if (!getIsHittingBlock()) break;
                }
                mc.gameMode.destroyDelay = 0;
            }

            dh.vrPlayer.blockDust(blockHit.getLocation().x, blockHit.getLocation().y, blockHit.getLocation().z,
                3 * totalHits, blockHit.getBlockPos(), blockstate, 0.6F, 1.0F);
        }

        dh.vr.triggerHapticPulse(device, 250 * totalHits);

        ClientNetworking.resetActiveBodyPart();
    }


    @Override
    public ProcessType processType() {
        return ProcessType.PER_TICK;
    }

    @Override
    public boolean isActive(@Nullable LocalPlayer player) {
        if (this.disableSwing > 0) {
            this.disableSwing--;
            return false;
        } else if (this.mc.gameMode == null) {
            return false;
        } else if (player == null) {
            return false;
        } else if (!player.isAlive()) {
            return false;
        } else if (player.isSleeping()) {
            return false;
        } else if (this.mc.screen != null) {
            return false;
        } else if (this.dh.vrSettings.weaponCollision ==
            VRSettings.WeaponCollision.OFF)
        {
            return false;
        } else if (
            this.dh.vrSettings.weaponCollision == VRSettings.WeaponCollision.AUTO &&
                player.isCreative())
        {
            return false;
        } else if (this.dh.vrSettings.seated) {
            return false;
        } else if (this.dh.vrSettings.getVrFreeMoveMode(false, this.dh.vrPlayer.vrdata_world_pre.fbtMode) ==
            VRSettings.FreeMove.RUN_IN_PLACE && player.zza > 0.0F)
        {
            return false;
        } else if (player.isBlocking() && !ClientNetworking.SERVER_ALLOWS_ATTACKING_WHILE_BLOCKING) {
            return false;
        } else {
            return !this.dh.jumpTracker.isjumping();
        }
    }

    @Override
    public void idleProcess(@Nullable LocalPlayer player) {
    }

    @Override
    public void activeProcess(@Nullable LocalPlayer player) {
        if (this.general == null && this.dh != null) {
            for (Tracker t : this.dh.getTrackers()) {
                if (t instanceof AbstractSwingTracker gs) {
                    attach(gs);
                    break;
                }
            }
        }
    }

    @Override
    public void inactiveProcess(@Nullable LocalPlayer player) {
        for (int i = 0; i < 4; i++) {
            this.lastWeaponSolid[i] = false;
            this.lastHitEntities[i] = Collections.emptyList();
            this.lastBlockHit[i] = null;
            this.lastAttackAABB[i] = null;
        }
        detach();
    }


    @Override
    public void renderDebug() {
    }

    private boolean getIsHittingBlock() {
        return mc.gameMode.isDestroying();
    }

    private void clearBlockHitDelay() {
    }

    private Vec3 constrain(Vec3 start, Vec3 end) {
        BlockHitResult blockhitresult = mc.level.clip(
            new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return blockhitresult.getType() == HitResult.Type.BLOCK ? blockhitresult.getLocation() : end;
    }

    private boolean isOpenable(BlockState state, Direction direction) {
        if (state.is(BlockTags.DOORS) || state.getBlock() instanceof DoorBlock) {
            Direction facing = state.getValue(DoorBlock.FACING);
            boolean open = state.getValue(DoorBlock.OPEN);
            DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);

            if (!open) {
                return facing == direction.getOpposite();
            } else {
                return switch (direction) {
                    case SOUTH -> (facing == Direction.WEST && hinge == DoorHingeSide.LEFT) ||
                        (facing == Direction.EAST && hinge == DoorHingeSide.RIGHT);
                    case NORTH -> (facing == Direction.EAST && hinge == DoorHingeSide.LEFT) ||
                        (facing == Direction.WEST && hinge == DoorHingeSide.RIGHT);
                    case EAST -> (facing == Direction.SOUTH && hinge == DoorHingeSide.LEFT) ||
                        (facing == Direction.NORTH && hinge == DoorHingeSide.RIGHT);
                    case WEST -> (facing == Direction.NORTH && hinge == DoorHingeSide.LEFT) ||
                        (facing == Direction.SOUTH && hinge == DoorHingeSide.RIGHT);
                    default -> false;
                };
            }
        } else if (state.is(BlockTags.TRAPDOORS) || state.getBlock() instanceof TrapDoorBlock) {
            Direction facing = state.getValue(TrapDoorBlock.FACING);
            boolean open = state.getValue(TrapDoorBlock.OPEN);
            return (!open && direction == Direction.DOWN) || (open && direction.getOpposite() == facing);
        } else if (state.is(BlockTags.FENCE_GATES) || state.getBlock() instanceof FenceGateBlock) {
            Direction facing = state.getValue(FenceGateBlock.FACING);
            boolean open = state.getValue(FenceGateBlock.OPEN);
            return !open && direction.getAxis() == facing.getAxis();
        }
        return false;
    }
}
