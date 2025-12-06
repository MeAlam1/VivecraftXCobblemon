// java
package org.vivecraft.api.utils;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.data.ViveItemTags;

/**
 * Helpers to check if an item or item stack is a "tool" for the VR client.
 *
 * @author MeAlam
 */
public class VRItemUtils {
    /**
     * Returns true if the given item stack should be treated as a tool.
     *
     * @param itemStack the item stack to check
     * @return true if the stack is a tool, false otherwise
     */
    public static boolean isTool(ItemStack itemStack) {
        return isToolItem(itemStack.getItem()) ||
            itemStack.is(ViveItemTags.VIVECRAFT_TOOLS) ||
            itemStack.is(ItemTags.PICKAXES) ||
            itemStack.is(ItemTags.AXES) ||
            itemStack.is(ItemTags.SHOVELS) ||
            itemStack.is(ItemTags.HOES);
    }

    /**
     * Returns true if the given item is considered a tool by type or identity.
     *
     * @param item the item to test
     * @return true if the item is a tool, false otherwise
     */
    private static boolean isToolItem(Item item) {
        return item instanceof DiggerItem ||
            item instanceof ArrowItem ||
            item instanceof FishingRodItem ||
            item instanceof FoodOnAStickItem ||
            item instanceof ShearsItem ||
            item == Items.BONE ||
            item == Items.BLAZE_ROD ||
            item == Items.BAMBOO ||
            item == Items.TORCH ||
            item == Items.REDSTONE_TORCH ||
            item == Items.STICK ||
            item == Items.DEBUG_STICK ||
            item instanceof FlintAndSteelItem ||
            item instanceof BrushItem;
    }

    /**
     * Returns the transparency for a held item.
     *
     * @param player    Player that is holding the item
     * @param itemStack held item
     * @return transparency for held items, between 0.1 and 1.0
     */
    public static float getItemFade(LocalPlayer player, ItemStack itemStack) {
        float fade = player.getAttackStrengthScale(0.0F) * 0.75F + 0.25F;

        if (player.isShiftKeyDown()) {
            fade = 0.75F;
        }

        /* TODO: Update to VanillaSwingListener
        if (ClientDataHolderVR.getInstance().swingTracker.lastWeaponSolid[ClientDataHolderVR.getInstance().isMainHand ?
            0 : 1])
        {
            fade -= 0.25F;
        }*/

        if (itemStack != ItemStack.EMPTY) {
            if (player.isBlocking() && player.getUseItem() != itemStack) {
                fade -= 0.25F;
            }

            if (itemStack.getItem() == Items.SHIELD && !player.isBlocking()) {
                fade -= 0.25F;
            }
        }

        if ((double) fade < 0.1D) {
            fade = 0.1F;
        }

        if (fade > 1.0F) {
            fade = 1.0F;
        }

        return fade;
    }
}
