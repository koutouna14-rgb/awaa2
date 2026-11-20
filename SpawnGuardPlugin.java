package com.spawnguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SpawnGuardPlugin extends JavaPlugin {

    private Zone zone;
    private Set<String> worlds = new HashSet<>();
    private Set<SpawnReason> allowedReasons = EnumSet.noneOf(SpawnReason.class);
    private boolean blockSpawning;
    private boolean blockTargeting;
    private boolean blockDamage;
    private boolean removeMobs;
    private boolean ignoreNamed;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new SafezoneListener(this), this);
        load();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    private void load() {
        reloadConfig();
        if (task != null) {
            task.cancel();
            task = null;
        }

        worlds = new HashSet<>(getConfig().getStringList("worlds"));
        blockSpawning = getConfig().getBoolean("block-spawning", true);
        blockTargeting = getConfig().getBoolean("block-targeting", true);
        blockDamage = getConfig().getBoolean("block-damage", true);
        removeMobs = getConfig().getBoolean("remove-mobs", true);
        ignoreNamed = getConfig().getBoolean("ignore-named-mobs", false);

        allowedReasons = EnumSet.noneOf(SpawnReason.class);
        for (String name : getConfig().getStringList("allowed-spawn-reasons")) {
            try {
                allowedReasons.add(SpawnReason.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unknown spawn reason in config: " + name);
            }
        }

        Set<String> regionIds = new HashSet<>();
        String single = getConfig().getString("region", "");
        if (single != null && !single.isBlank()) regionIds.add(single.toLowerCase(Locale.ROOT));
        for (String r : getConfig().getStringList("regions")) {
            if (!r.isBlank()) regionIds.add(r.toLowerCase(Locale.ROOT));
        }

        if (!regionIds.isEmpty() && Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            zone = new WorldGuardZone(regionIds);
            getLogger().info("Using WorldGuard region(s): " + regionIds);
            Bukkit.getScheduler().runTask(this, () -> warnIfRegionMissing(regionIds));
        } else {
            zone = new RadiusZone(
                    getConfig().getBoolean("radius.use-world-spawn", true),
                    getConfig().getDouble("radius.x", 0),
                    getConfig().getDouble("radius.z", 0),
                    getConfig().getDouble("radius.size", 64));
            getLogger().info("Using radius zone (no WorldGuard region configured, or WorldGuard missing).");
        }

        if (!worlds.isEmpty()) {
            for (String wn : worlds) {
                if (Bukkit.getWorld(wn) == null) {
                    getLogger().warning("World '" + wn + "' from config 'worlds' does not exist. Loaded worlds: "
                            + Bukkit.getWorlds().stream().map(World::getName).toList());
                }
            }
        }

        if (removeMobs) {
            long interval = Math.max(5L, getConfig().getLong("check-interval-ticks", 20L));
            task = Bukkit.getScheduler().runTaskTimer(this, this::sweep, interval, interval);
        }
    }

    private void warnIfRegionMissing(Set<String> regionIds) {
        try {
            var container = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            for (World w : targetWorlds()) {
                var manager = container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(w));
                if (manager == null) continue;
                for (String id : regionIds) {
                    if (manager.getRegion(id) == null) {
                        getLogger().warning("WorldGuard region '" + id + "' was NOT found in world '" + w.getName()
                                + "'. Existing regions there: " + manager.getRegions().keySet()
                                + " -> SpawnGuard will not protect anything for that region!");
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Could not verify WorldGuard region: " + t.getMessage());
        }
    }

    private List<World> targetWorlds() {
        if (worlds.isEmpty()) return Bukkit.getWorlds();
        List<World> list = new java.util.ArrayList<>();
        for (String n : worlds) {
            World w = Bukkit.getWorld(n);
            if (w != null) list.add(w);
        }
        return list;
    }

    /** Removes hostile mobs that are inside the zone (and drops targets on any that are kept). */
    private void sweep() {
        for (World w : targetWorlds()) {
            for (LivingEntity e : w.getLivingEntities()) {
                if (!isHostile(e)) continue;
                if (!zone.contains(e.getLocation())) {
                    // Mob outside the zone but chasing a player who is inside it
                    if (blockTargeting && e instanceof Mob m && m.getTarget() instanceof Player p && zone.contains(p.getLocation())) {
                        m.setTarget(null);
                    }
                    continue;
                }
                if (ignoreNamed && e.getCustomName() != null) {
                    if (e instanceof Mob m) m.setTarget(null);
                    continue;
                }
                e.remove();
            }
        }
    }

    boolean isHostile(Entity e) {
        return e instanceof Enemy && !(e instanceof Boss);
    }

    boolean inZone(Location loc) {
        return loc.getWorld() != null && (worlds.isEmpty() || worlds.contains(loc.getWorld().getName())) && zone.contains(loc);
    }

    boolean blockSpawning() { return blockSpawning; }
    boolean blockTargeting() { return blockTargeting; }
    boolean blockDamage() { return blockDamage; }
    Set<SpawnReason> allowedReasons() { return allowedReasons; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages.reloaded", "&aSpawnGuard reloaded.")));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Run this in-game.");
                return true;
            }
            Location loc = p.getLocation();
            p.sendMessage(ChatColor.YELLOW + "--- SpawnGuard debug ---");
            p.sendMessage("World: " + loc.getWorld().getName() + " | configured worlds: "
                    + (worlds.isEmpty() ? "ALL" : worlds.toString()));
            p.sendMessage("Zone type: " + zone.getClass().getSimpleName());
            if (zone instanceof WorldGuardZone) {
                try {
                    p.sendMessage("WorldGuard regions at your feet: " + WorldGuardZone.regionsAt(loc));
                } catch (Throwable t) {
                    p.sendMessage("Could not read regions: " + t.getMessage());
                }
            }
            p.sendMessage("You are inside the protected zone: "
                    + (inZone(loc) ? ChatColor.GREEN + "YES" : ChatColor.RED + "NO"));
            int shown = 0;
            for (Entity e : p.getNearbyEntities(32, 32, 32)) {
                if (!isHostile(e)) continue;
                if (shown++ >= 10) break;
                p.sendMessage(" - " + e.getType() + " named=" + (e.getCustomName() != null)
                        + " inZone=" + inZone(e.getLocation()));
            }
            if (shown == 0) p.sendMessage("No hostile mobs within 32 blocks.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <reload|debug>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("reload", "debug") : List.of();
    }
}
