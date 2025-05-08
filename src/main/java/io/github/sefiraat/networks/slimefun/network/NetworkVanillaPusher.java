package io.github.sefiraat.networks.slimefun.network;

import com.bgsoftware.wildchests.api.WildChestsAPI;
import io.github.sefiraat.networks.NetworkStorage;
import io.github.sefiraat.networks.Networks;
import io.github.sefiraat.networks.network.NodeDefinition;
import io.github.sefiraat.networks.network.NodeType;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public class NetworkVanillaPusher extends NetworkDirectional {

    private static final int[] BACKGROUND_SLOTS = new int[]{
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 15, 16, 17, 18, 20, 22, 23, 24, 26, 27, 28, 30, 31, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44
    };
    private static final int INPUT_SLOT = 25;
    private static final int NORTH_SLOT = 11;
    private static final int SOUTH_SLOT = 29;
    private static final int EAST_SLOT = 21;
    private static final int WEST_SLOT = 19;
    private static final int UP_SLOT = 14;
    private static final int DOWN_SLOT = 32;

    private static final boolean WILDCHESTS_ENABLED = Networks.getSupportedPluginManager().isWildChests();
    private static final boolean DEBUG_ENABLED = Networks.getInstance().getConfig().getBoolean("debug", false);

    private UUID cachedOwnerUUID;
    private Boolean isWildChest;
    private int tickCounter = 0;
    private boolean hasPermission = true;

    public NetworkVanillaPusher(ItemGroup itemGroup,
                                SlimefunItemStack item,
                                RecipeType recipeType,
                                ItemStack[] recipe
    ) {
        super(itemGroup, item, recipeType, recipe, NodeType.PUSHER);
        this.getSlotsToDrop().add(INPUT_SLOT);
    }

    @Override
    protected void onTick(@Nullable BlockMenu blockMenu, @Nonnull Block block) {
        super.onTick(blockMenu, block);
        if (blockMenu != null) {
            final NodeDefinition definition = NetworkStorage.getAllNetworkObjects().get(blockMenu.getLocation());
            tryPushItem(blockMenu, definition);
        }
    }

    private void tryPushItem(@Nonnull BlockMenu blockMenu, @Nullable NodeDefinition definition) {
        // Early exit if no item to push
        final ItemStack stack = blockMenu.getItemInSlot(INPUT_SLOT);
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }

        if (definition == null || definition.getNode() == null) {
            return;
        }

        final BlockFace direction = getCurrentDirection(blockMenu);
        final Block block = blockMenu.getBlock();
        final Block targetBlock = blockMenu.getBlock().getRelative(direction);

        // Cache owner UUID
        if (cachedOwnerUUID == null) {
            String uuidString = BlockStorage.getLocationInfo(block.getLocation(), OWNER_KEY);
            cachedOwnerUUID = uuidString != null ? UUID.fromString(uuidString) : null;
        }
        if (cachedOwnerUUID == null) {
            return;
        }

        final OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(cachedOwnerUUID);

        // Throttle permission checks
        if (tickCounter % 5 == 0) {
            hasPermission = Slimefun.getProtectionManager().hasPermission(offlinePlayer, targetBlock, Interaction.INTERACT_BLOCK);
        }
        tickCounter++;

        if (!hasPermission) {
            return;
        }

        final BlockState blockState = targetBlock.getState();
        if (!(blockState instanceof InventoryHolder holder)) {
            return;
        }

        final Inventory inventory = holder.getInventory();

        // Cache WildChests check
        if (WILDCHESTS_ENABLED) {
            if (isWildChest == null) {
                isWildChest = WildChestsAPI.getChest(targetBlock.getLocation()) != null;
            }
        } else {
            isWildChest = false;
        }

        sendDebugMessage(block.getLocation(), "WildChests detected: " + WILDCHESTS_ENABLED);
        sendDebugMessage(block.getLocation(), "Block detected as chest: " + isWildChest);

        if (inventory instanceof FurnaceInventory furnace) {
            handleFurnace(stack, furnace);
        } else if (inventory instanceof BrewerInventory brewer) {
            handleBrewingStand(stack, brewer);
        } else if (WILDCHESTS_ENABLED && isWildChest) {
            sendDebugMessage(block.getLocation(), "WildChest test failed, escaping");
            return;
        } else {
            sendDebugMessage(block.getLocation(), "WildChest test passed.");
            ItemStack remaining = holder.getInventory().addItem(stack.clone()).get(0);
            if (remaining == null || remaining.getAmount() == 0) {
                stack.setAmount(0); // All items were added
            } else {
                stack.setAmount(remaining.getAmount()); // Update remaining amount
            }
        }
    }

    private void handleFurnace(@Nonnull ItemStack stack, @Nonnull FurnaceInventory furnace) {
        if (stack.getType().isFuel() && (furnace.getFuel() == null || furnace.getFuel().getType() == Material.AIR)) {
            furnace.setFuel(stack.clone());
            stack.setAmount(0);
        } else if (!stack.getType().isFuel() && (furnace.getSmelting() == null || furnace.getSmelting().getType() == Material.AIR)) {
            furnace.setSmelting(stack.clone());
            stack.setAmount(0);
        }
    }

    private void handleBrewingStand(@Nonnull ItemStack stack, @Nonnull BrewerInventory brewer) {
        if (stack.getType() == Material.BLAZE_POWDER) {
            if (brewer.getFuel() == null || brewer.getFuel().getType() == Material.AIR) {
                brewer.setFuel(stack.clone());
                stack.setAmount(0);
            } else if (brewer.getIngredient() == null || brewer.getIngredient().getType() == Material.AIR) {
                brewer.setIngredient(stack.clone());
                stack.setAmount(0);
            }
        } else if (stack.getType() == Material.POTION) {
            for (int i = 0; i < 3; i++) {
                final ItemStack stackInSlot = brewer.getContents()[i];
                if (stackInSlot == null || stackInSlot.getType() == Material.AIR) {
                    final ItemStack[] contents = brewer.getContents();
                    contents[i] = stack.clone();
                    brewer.setContents(contents);
                    stack.setAmount(0);
                    return;
                }
            }
        } else if (brewer.getIngredient() == null || brewer.getIngredient().getType() == Material.AIR) {
            brewer.setIngredient(stack.clone());
            stack.setAmount(0);
        }
    }

    public void sendDebugMessage(Location location, String message) {
        if (DEBUG_ENABLED) {
            Networks.getInstance().getLogger().info("[DEBUG] " + location + ": " + message);
        }
    }

    @Nonnull
    @Override
    protected int[] getBackgroundSlots() {
        return BACKGROUND_SLOTS;
    }

    @Override
    public int getNorthSlot() {
        return NORTH_SLOT;
    }

    @Override
    public int getSouthSlot() {
        return SOUTH_SLOT;
    }

    @Override
    public int getEastSlot() {
        return EAST_SLOT;
    }

    @Override
    public int getWestSlot() {
        return WEST_SLOT;
    }

    @Override
    public int getUpSlot() {
        return UP_SLOT;
    }

    @Override
    public int getDownSlot() {
        return DOWN_SLOT;
    }

    @Override
    public boolean runSync() {
        return true;
    }

    @Override
    public int[] getInputSlots() {
        return new int[]{INPUT_SLOT};
    }

    @Override
    protected Particle.DustOptions getDustOptions() {
        return new Particle.DustOptions(Color.MAROON, 1);
    }
}