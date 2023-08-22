package org.figuramc.figura.utils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.mixin.ClientLevelInvoker;
import org.figuramc.figura.mixin.EntityAccessor;
import org.figuramc.figura.mixin.gui.PlayerTabOverlayAccessor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class EntityUtils {

    private static GameProfileCache PROFILE_CACHE;
    private static final Cache<UUID, UUID> REAL_ENTITY_UUID_CACHE = CacheBuilder.newBuilder()
            .expireAfterAccess(5L, TimeUnit.MINUTES)
            .build();

    private static final Map<UUID, CompletableFuture<UUID>> PLAYER_UUID_REQUESTS = Maps.newConcurrentMap();

    public static UUID getEntityUUIDSync(Entity entity) {
        try {
            return getEntityUUID(entity).get();
        } catch (Exception e) {
            e.printStackTrace();
            return entity.getUUID();
        }
    }

    public static CompletableFuture<UUID> getEntityUUID(Entity entity) {
        if (entity instanceof Player && EntityUtils.checkInvalidPlayer(entity.getUUID())) {
            return requestPlayerOnlineUUID(entity.getUUID());
        }

        return CompletableFuture.completedFuture(entity.getUUID());
    }

    public static UUID getPlayerOnlineUUIDSync(UUID uuid) {
        try {
            return getPlayerOnlineUUID(uuid).get();
        } catch (Exception e) {
            e.printStackTrace();
            return uuid;
        }
    }

    public static CompletableFuture<UUID> getPlayerOnlineUUID(UUID uuid) {
        if (EntityUtils.checkInvalidPlayer(uuid)) {
            return requestPlayerOnlineUUID(uuid);
        }

        return CompletableFuture.completedFuture(uuid);
    }

    private synchronized static CompletableFuture<UUID> requestPlayerOnlineUUID(@NotNull UUID uuid) {
        PlayerInfo playerInfo = EntityUtils.getPlayerInfo(uuid);
        if (playerInfo == null) return CompletableFuture.completedFuture(uuid);

        if (PROFILE_CACHE == null) {
            PROFILE_CACHE = new GameProfileCache();
        }

        var cachedProfile = PROFILE_CACHE.getProfileByNameNow(playerInfo.getProfile().getName());
        if (cachedProfile.isPresent()) {
            REAL_ENTITY_UUID_CACHE.put(cachedProfile.get().getId(), uuid);
            return CompletableFuture.completedFuture(cachedProfile.get().getId());
        }

        return PLAYER_UUID_REQUESTS.computeIfAbsent(uuid, (playerUUID) -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    var onlineUUID = PROFILE_CACHE.getProfileByName(playerInfo.getProfile().getName())
                            .map(gameProfile -> {
                                REAL_ENTITY_UUID_CACHE.put(gameProfile.getId(), uuid);
                                return gameProfile.getId();
                            })
                            .orElse(uuid);

                    PLAYER_UUID_REQUESTS.remove(uuid);
                    return onlineUUID;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    public static void clearEntityUUIDCache() {
        REAL_ENTITY_UUID_CACHE.invalidateAll();
    }

    public static Entity getEntityByUUID(UUID uuid) {
        if (Minecraft.getInstance().level == null)
            return null;

        UUID realUuid = REAL_ENTITY_UUID_CACHE.getIfPresent(uuid);
        if (realUuid != null) uuid = realUuid;

        return ((ClientLevelInvoker) Minecraft.getInstance().level).getEntityGetter().get(uuid);
    }

    public static Entity getViewedEntity(float distance) {
        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null) return null;

        float tickDelta = Minecraft.getInstance().getFrameTime();
        Vec3 entityEye = entity.getEyePosition(tickDelta);
        Vec3 viewVec = entity.getViewVector(tickDelta).scale(distance);
        AABB box = entity.getBoundingBox().expandTowards(viewVec).inflate(1f, 1f, 1f);

        Vec3 raycastEnd = entityEye.add(viewVec);

        double raycastDistanceSquared; // Has to be squared for some reason, thanks minecraft for not making that clear
        BlockHitResult blockResult = ((EntityAccessor) entity).getLevel().clip(new ClipContext(entityEye, raycastEnd, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
        if (blockResult != null)
            raycastDistanceSquared = blockResult.getLocation().distanceToSqr(entityEye);
        else
            raycastDistanceSquared = distance * distance;

        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(entity, entityEye, raycastEnd, box, entity1 -> !entity1.isSpectator() && entity1.isPickable(), raycastDistanceSquared);
        if (entityHitResult != null)
            return entityHitResult.getEntity();
        return null;
    }

    public static PlayerInfo getPlayerInfo(UUID uuid) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection == null ? null : connection.getPlayerInfo(uuid);
    }

    public static String getNameForUUID(UUID uuid) {
        PlayerInfo player = getPlayerInfo(uuid);
        if (player != null)
            return player.getProfile().getName();

        Entity e = getEntityByUUID(uuid);
        if (e != null)
            return e.getName().getString();

        return null;
    }

    public static Map<String, UUID> getPlayerList() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || connection.getOnlinePlayerIds().isEmpty())
            return Map.of();

        Map<String, UUID> playerList = new HashMap<>();

        for (UUID uuid : connection.getOnlinePlayerIds()) {
            PlayerInfo player = connection.getPlayerInfo(uuid);
            if (player != null)
                playerList.put(player.getProfile().getName(), uuid);
        }

        return playerList;
    }

    public static List<PlayerInfo> getTabList() {
        return ((PlayerTabOverlayAccessor) Minecraft.getInstance().gui.getTabList()).getThePlayerInfos();
    }

    public static boolean checkInvalidPlayer(UUID id) {
        if (id.version() != 4)
            return true;

        PlayerInfo playerInfo = getPlayerInfo(id);
        if (playerInfo == null)
            return false;

        GameProfile profile = playerInfo.getProfile();
        String name = profile.getName();
        return name != null && (name.isBlank() || name.charAt(0) == '\u0000');
    }
}
