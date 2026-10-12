package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.ability.ActorCondition;
import com.gpl.rpg.AndorsTrail.model.ability.ActorConditionEffect;
import com.gpl.rpg.AndorsTrail.model.ability.ActorConditionType;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.actor.MonsterType;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.model.item.ItemType;
import com.gpl.rpg.AndorsTrail.model.item.Loot;
import com.gpl.rpg.AndorsTrail.util.ConstRange;

/**
 * Vampire mechanics deliberately piggyback on existing save-safe systems:
 * permanent/temporary actor conditions plus WorldData timers.
 * No new Player save fields are introduced.
 */
public final class VampirismController {
    public static final String CONDITION_INFECTION = "vampiric_infection";
    public static final String CONDITION_VAMPIRISM = "vampirism";
    public static final String CONDITION_SATED = "vampire_sated";
    public static final String CONDITION_THIRSTY = "vampire_thirsty";
    public static final String CONDITION_STARVING = "vampire_starving";
    public static final String ITEM_NIGHTBLOOD = "nightblood_vial";

    private static final String TIMER_LAST_FEED = "vampire_last_feed";

    // World rounds since the last feeding.
    private static final int THIRSTY_AFTER_ROUNDS = 16;
    private static final int STARVING_AFTER_ROUNDS = 36;

    private VampirismController() {}

    public static boolean isVampire(Player player) {
        return hasCondition(player, CONDITION_VAMPIRISM);
    }

    public static boolean isInfected(Player player) {
        return hasCondition(player, CONDITION_INFECTION);
    }

    /**
     * Called after world time advances but before ordinary condition durations
     * tick down. That lets a 1-round infection transform instead of simply
     * disappearing.
     */
    public static void onPlayerRound(ControllerContext controllers, WorldContext world) {
        if (world == null || world.model == null || world.model.player == null) return;
        final Player player = world.model.player;

        ActorCondition infection = findCondition(player, CONDITION_INFECTION);
        if (infection != null && !isVampire(player) && infection.duration <= 1) {
            transform(controllers, world, player);
            return;
        }

        if (!isVampire(player)) {
            clearHungerConditions(controllers, world, player);
            world.model.worldData.removeTimer(TIMER_LAST_FEED);
            return;
        }

        if (!world.model.worldData.hasTimerElapsed(TIMER_LAST_FEED, 0)) {
            world.model.worldData.createTimer(TIMER_LAST_FEED);
        }

        if (world.model.worldData.hasTimerElapsed(TIMER_LAST_FEED, STARVING_AFTER_ROUNDS)) {
            setHungerCondition(controllers, world, player, CONDITION_STARVING);
            // Starvation is dangerous but will not directly kill the player.
            if (player.health.current > 1) {
                controllers.actorStatsController.removeActorHealth(player, 1);
            }
        } else if (world.model.worldData.hasTimerElapsed(TIMER_LAST_FEED, THIRSTY_AFTER_ROUNDS)) {
            setHungerCondition(controllers, world, player, CONDITION_THIRSTY);
        } else {
            setHungerCondition(controllers, world, player, CONDITION_SATED);
            controllers.actorStatsController.addActorHealth(player, 1);
        }
    }

    /**
     * Called before the slain monster is removed. Vampires automatically feed
     * from living prey; non-vampires discover one Nightblood vial from undead
     * remains, giving the player an explicit choice whether to risk infection.
     */
    public static void onPlayerKilledMonster(
            ControllerContext controllers,
            WorldContext world,
            Monster killedMonster,
            Loot loot) {
        if (world == null || world.model == null || killedMonster == null) return;
        final Player player = world.model.player;

        if (isVampire(player)) {
            if (isFeedable(killedMonster)) {
                world.model.worldData.createTimer(TIMER_LAST_FEED);
                setHungerCondition(controllers, world, player, CONDITION_SATED);
                controllers.actorStatsController.addActorHealth(player, 4);
                world.model.combatLog.append("You feed on " + killedMonster.monsterType.name + ". The hunger recedes.");
            }
            return;
        }

        if (isInfected(player)) return;
        if (killedMonster.monsterType.monsterClass != MonsterType.MonsterClass.undead) return;
        if (player.inventory.hasItem(ITEM_NIGHTBLOOD)) return;

        ItemType nightblood = world.itemTypes.getItemType(ITEM_NIGHTBLOOD);
        if (nightblood == null) return;

        loot.add(nightblood, 1);
        world.model.combatLog.append("You recover a vial of unnaturally cold blood from the undead remains.");
    }

    private static boolean isFeedable(Monster monster) {
        if (monster.monsterType == null) return false;
        switch (monster.monsterType.monsterClass) {
            case humanoid:
            case animal:
            case giant:
            case reptile:
                return true;
            default:
                return false;
        }
    }

    private static void transform(ControllerContext controllers, WorldContext world, Player player) {
        removeCondition(controllers, world, player, CONDITION_INFECTION);
        applyCondition(controllers, world, player, CONDITION_VAMPIRISM, ActorCondition.DURATION_FOREVER);
        world.model.worldData.createTimer(TIMER_LAST_FEED);
        setHungerCondition(controllers, world, player, CONDITION_SATED);
        controllers.actorStatsController.addActorHealth(player, 5);
        world.model.combatLog.append("The blood curse takes hold. Your heartbeat slows, and a terrible hunger awakens. You have become a vampire.");
    }

    private static void setHungerCondition(
            ControllerContext controllers,
            WorldContext world,
            Player player,
            String wanted) {
        boolean changed = false;
        for (String id : new String[] { CONDITION_SATED, CONDITION_THIRSTY, CONDITION_STARVING }) {
            if (!id.equals(wanted) && hasCondition(player, id)) {
                removeCondition(controllers, world, player, id);
                changed = true;
            }
        }
        if (!hasCondition(player, wanted)) {
            applyCondition(controllers, world, player, wanted, ActorCondition.DURATION_FOREVER);
            changed = true;
        }
        if (changed) {
            controllers.actorStatsController.recalculatePlayerStats(player);
        }
    }

    private static void clearHungerConditions(
            ControllerContext controllers,
            WorldContext world,
            Player player) {
        for (String id : new String[] { CONDITION_SATED, CONDITION_THIRSTY, CONDITION_STARVING }) {
            if (hasCondition(player, id)) removeCondition(controllers, world, player, id);
        }
    }

    private static void applyCondition(
            ControllerContext controllers,
            WorldContext world,
            Player player,
            String id,
            int duration) {
        ActorConditionType type = world.actorConditionsTypes.getActorConditionType(id);
        if (type == null) return;
        controllers.actorStatsController.applyActorCondition(
                player,
                new ActorConditionEffect(type, 1, duration, new ConstRange(100, 100)));
    }

    private static void removeCondition(
            ControllerContext controllers,
            WorldContext world,
            Player player,
            String id) {
        ActorConditionType type = world.actorConditionsTypes.getActorConditionType(id);
        if (type == null) return;
        controllers.actorStatsController.applyActorCondition(
                player,
                new ActorConditionEffect(
                        type,
                        ActorCondition.MAGNITUDE_REMOVE_ALL,
                        ActorCondition.DURATION_NONE,
                        new ConstRange(100, 100)));
    }

    private static boolean hasCondition(Player player, String id) {
        return findCondition(player, id) != null;
    }

    private static ActorCondition findCondition(Player player, String id) {
        for (ActorCondition c : player.conditions) {
            if (c.conditionType != null && id.equals(c.conditionType.conditionTypeID)) return c;
        }
        return null;
    }
}
