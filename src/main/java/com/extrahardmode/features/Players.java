/*
 * This file is part of
 * ExtraHardMode Server Plugin for Minecraft
 *
 * Copyright (C) 2012 Ryan Hamshire
 * Copyright (C) 2013 Diemex
 *
 * ExtraHardMode is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ExtraHardMode is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero Public License
 * along with ExtraHardMode.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.extrahardmode.features;


import com.extrahardmode.ExtraHardMode;
import com.extrahardmode.config.RootConfig;
import com.extrahardmode.config.RootNode;
import com.extrahardmode.events.EhmPlayerExtinguishFireEvent;
import com.extrahardmode.events.EhmPlayerInventoryLossEvent;
import com.extrahardmode.module.DataStoreModule;
import com.extrahardmode.module.PlayerData;
import com.extrahardmode.module.PlayerModule;
import com.extrahardmode.service.Feature;
import com.extrahardmode.service.ListenerModule;
import com.extrahardmode.service.config.customtypes.BlockType;
import com.extrahardmode.service.config.customtypes.BlockTypeList;
import com.extrahardmode.service.config.customtypes.PotionEffectHolder;
import com.extrahardmode.task.SetPlayerHealthAndFoodTask;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;


/**
 * Playerchanges include
 * <p/>
 * less health/food on respawn , loss of some of their inventory , enhanced environmental damage , catching the player
 * on fire if extinguishing fires by hand
 */
public class Players extends ListenerModule
{
    private RootConfig CFG = null;

    private PlayerModule playerModule;


    /**
     * Constructor
     */
    public Players(ExtraHardMode plugin)
    {
        super(plugin);
    }


    @Override
    public void starting()
    {
        super.starting();
        CFG = plugin.getModuleForClass(RootConfig.class);
        playerModule = plugin.getModuleForClass(PlayerModule.class);
    }


    /**
     * FEATURE: respawning players start without full health or food
     *
     * @param respawnEvent - Event that occurred.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerRespawn(PlayerRespawnEvent respawnEvent)
    {
        Player player = respawnEvent.getPlayer();
        World world = player.getWorld();

        final boolean playerBypasses = playerModule.playerBypasses(player, Feature.RESPAWN_FOOD_HEALTH);

        final int respawnHealthPercentage = playerBypasses ? 100
                : CFG.getInt(RootNode.PLAYER_RESPAWN_HEALTH_PERCENTAGE, world.getName());
        final int respawnFood = playerBypasses ? 20
                : CFG.getInt(RootNode.PLAYER_RESPAWN_FOOD_LEVEL, world.getName());

        if (respawnFood < 20 && respawnHealthPercentage > 0 && respawnHealthPercentage < 100)
        {
            //TODO HIGH EhmPlayerRespawnEvent
            SetPlayerHealthAndFoodTask task = new SetPlayerHealthAndFoodTask(player, (int) player.getMaxHealth() * respawnHealthPercentage / 100, respawnFood);
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task, 10L); // half-second delay
        }
        // FEATURE: players can't swim when they're carrying a lot of weight, reset the cached value
        PlayerData playerData = plugin.getModuleForClass(DataStoreModule.class).getPlayerData(player.getName());
        playerData.cachedWeightStatus = -1.0F;
    }


    /**
     * When a Player dies he looses a percentage of his inventory
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(PlayerDeathEvent event)
    {
        Player player = event.getEntity();
        World world = player.getWorld();

        final boolean enableItemLoss = CFG.getBoolean(RootNode.PLAYER_DEATH_ITEMS_FORFEIT_ENABLE, world.getName());
        final int deathLossPercent = CFG.getInt(RootNode.PLAYER_DEATH_ITEM_STACKS_FORFEIT_PERCENT, world.getName());
        final boolean playerBypasses = playerModule.playerBypasses(player, Feature.DEATH_INV_LOSS);

        final int toolDmgPercent = CFG.getInt(RootNode.PLAYER_DEATH_ITEM_STACKS_FORFEIT_PERCENT, world.getName());
        final BlockTypeList blacklisted = CFG.getBlocktypeList(RootNode.PLAYER_DEATH_ITEMS_BLACKLIST, world.getName());
        final BlockTypeList toolIds = CFG.getBlocktypeList(RootNode.PLAYER_DEATH_TOOLS_LIST, world.getName());
        final boolean destroyTools = CFG.getBoolean(RootNode.PLAYER_DEATH_TOOLS_KEEP_DAMAGED, world.getName());

        // FEATURE: some portion of player inventory is permanently lost on death
        if (!playerBypasses && enableItemLoss)
        {
            List<ItemStack> drops = event.getDrops();
            List<ItemStack> removedDrops = new ArrayList<ItemStack>();

            int numberOfStacksToRemove = (int) (drops.size() * (deathLossPercent / 100.0f));
            loop:
            for (int i = 0; i < numberOfStacksToRemove && drops.size() > 0; i++)
            {
                ItemStack toRemove = drops.get(plugin.getRandom().nextInt(drops.size()));
                for (BlockType block : blacklisted.toArray())
                    if (block.matches(toRemove))
                        continue loop; //don't remove blacklisted items
                removedDrops.add(toRemove);
            }
            EhmPlayerInventoryLossEvent inventoryLossEvent = new EhmPlayerInventoryLossEvent(event, drops, removedDrops);
            plugin.getServer().getPluginManager().callEvent(inventoryLossEvent);

            if (!inventoryLossEvent.isCancelled())
            {
                List<ItemStack> evntDrops = inventoryLossEvent.getDrops();
                List<ItemStack> evntDropsRemove = inventoryLossEvent.getStacksToRemove();
                outer:
                for (ItemStack item : evntDropsRemove)
                {
                    for (BlockType tool : toolIds.toArray())
                    {
                        //Damage valuable tools instead of completely destroying them
                        if (tool.matches(item))
                        {
                            short dur = item.getDurability();
                            short maxDurability = item.getType().getMaxDurability();
                            dur += maxDurability / 100 * toolDmgPercent;
                            //Prevent complete destroyal of heavily damaged items
                            if (dur >= maxDurability && !destroyTools)
                                dur = --maxDurability;
                            item.setDurability(dur);
                            continue outer;
                        }
                    }
                    evntDrops.remove(item);
                }
            }
        }
    }


    /**
     * Environmental effects when player is damaged
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)//so we know if the event got cancelled
    public void onEntityDamage(EntityDamageEvent event)
    {
        Entity entity = event.getEntity();
        World world = entity.getWorld();

        if (entity instanceof Player)
        {
            Player player = (Player) entity;
            final boolean enhancedEnvironmentalDmg = CFG.getBoolean(RootNode.ENHANCED_ENVIRONMENTAL_DAMAGE, world.getName());
            final boolean playerBypasses = playerModule.playerBypasses(player, Feature.ENVIRONMENTAL_EFFECTS);

            // FEATURE: extra damage and effects from environmental damage
            if (enhancedEnvironmentalDmg && !playerBypasses)
            {
                EntityDamageEvent.DamageCause cause = event.getCause();

                switch (cause)
                {
                    case BLOCK_EXPLOSION:
                    case ENTITY_EXPLOSION:
                        //TODO EhmPlayerEnvironmentalDamageEvent for each type
                        if (event.getDamage() > 2)
                            applyEffectOnDmg(event, CFG.getPotionEffect(RootNode.ENHANCED_DMG_EXPLOSION, world.getName()), CFG.getDouble(RootNode.ENHANCED_DMG_EXPLOSION_MULT, world.getName()));
                        break;
                    case FALL:
                        applyEffectOnDmg(event, CFG.getPotionEffect(RootNode.ENHANCED_DMG_FALL, world.getName()), CFG.getDouble(RootNode.ENHANCED_DMG_FALL_MULT, world.getName()));
//                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, (int) (20 * event.getDamage()), 4));
//                        event.setDamage(event.getDamage() * 2);
                        break;
                    case SUFFOCATION:
                        if (player.getVehicle() instanceof Horse)  //Reduced because you can easily glitch into blocks
                            applyEffectOnDmg(event, CFG.getPotionEffect(RootNode.ENHANCED_DMG_SUFFOCATION, world.getName()), CFG.getDouble(RootNode.ENHANCED_DMG_SUFFOCATION_MULT, world.getName()) / 2);
                        else
                            applyEffectOnDmg(event, CFG.getPotionEffect(RootNode.ENHANCED_DMG_SUFFOCATION, world.getName()), CFG.getDouble(RootNode.ENHANCED_DMG_SUFFOCATION_MULT, world.getName()));
//                        event.setDamage(event.getDamage() * 5);
                        break;
                    case LAVA:
                        applyEffectOnDmg(event, CFG.getPotionEffect(RootNode.ENHANCED_DMG_LAVA, world.getName()), CFG.getDouble(RootNode.ENHANCED_DMG_LAVA_MULT, world.getName()));
//                        event.setDamage(event.getDamage() * 2);
                        break;
                    case FIRE_TICK:
                        applyEffectOnDmg(event, CFG.getPotionEffect(RootNode.ENHANCED_DMG_BURN, world.getName()), CFG.getDouble(RootNode.ENHANCED_DMG_BURN_MULT, world.getName()));
//                        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 1));
                        break;
                }
            }
        }


    }


    private void applyEffectOnDmg(EntityDamageEvent event, PotionEffectHolder potionEffect, double multiplier)
    {
        //Assume it's a LivingEntity,
        potionEffect.applyEffect((LivingEntity) event.getEntity(), false);
        event.setDamage((int) (event.getDamage() * multiplier));
    }


    /**
     * when a player interacts with the world
     *
     * @param event - Event that occurred.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    void onPlayerInteract(PlayerInteractEvent event)
    {
        Player player = event.getPlayer();
        World world = event.getPlayer().getWorld();
        Block block = event.getClickedBlock();
        Action action = event.getAction();

        final boolean extinguishingFireIgnites = CFG.getBoolean(RootNode.EXTINGUISHING_FIRE_IGNITES_PLAYERS, world.getName());

        final boolean playerBypasses = playerModule.playerBypasses(player, Feature.DANGEROUS_FIRES);

        // FEATURE: putting out fire up close catches the player on fire
        if (extinguishingFireIgnites && !playerBypasses && block != null && block.getType() != Material.AIR &&
                (action.equals(Action.LEFT_CLICK_BLOCK) || action.equals(Action.LEFT_CLICK_AIR)))
        {
            if (block.getRelative(event.getBlockFace()).getType() == Material.FIRE)
            {
                EhmPlayerExtinguishFireEvent fireEvent = new EhmPlayerExtinguishFireEvent(player, 100);  // 20L ~ 1 seconds; 100L ~ 5 seconds
                plugin.getServer().getPluginManager().callEvent(fireEvent);
                if (!fireEvent.isCancelled())
                    player.setFireTicks(fireEvent.getBurnTicks());
            }
        }
    }
}
