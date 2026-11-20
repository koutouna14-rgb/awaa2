package com.spawnguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Only loaded when WorldGuard is present, so the plugin still runs without it. */
public class WorldGuardZone implements Zone {

    private final Set<String> regionIds; // lowercase

    public WorldGuardZone(Set<String> regionIds) {
        this.regionIds = regionIds;
    }

    @Override
    public boolean contains(Location loc) {
        if (loc.getWorld() == null) return false;
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        return query.getApplicableRegions(BukkitAdapter.adapt(loc))
                .getRegions()
                .stream()
                .anyMatch(r -> regionIds.contains(r.getId().toLowerCase()));
    }

    /** Names of every WorldGuard region at a location (used by /spawnguard debug). */
    public static List<String> regionsAt(Location loc) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.createQuery().getApplicableRegions(BukkitAdapter.adapt(loc))
                .getRegions().stream().map(r -> r.getId()).collect(Collectors.toList());
    }
}
