package org.figuramc.figura.utils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.core.UUIDUtil;
import org.figuramc.figura.FiguraMod;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class GameProfileCache {

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(GameProfileInfo.class, new GameProfileInfo.Serializer())
            .create();

    private final GameProfileRepository profileRepository;
    private final File cacheFile = new File("figura/cache/usercache.json");

    private final Map<String, GameProfileInfo> profilesByName = Maps.newConcurrentMap();
    private final Map<UUID, GameProfileInfo> profilesByUUID = Maps.newConcurrentMap();

    private final AtomicLong operationCount = new AtomicLong();

    public GameProfileCache() {
        var authenticationService = new YggdrasilAuthenticationService(Proxy.NO_PROXY);
        this.profileRepository = authenticationService.createProfileRepository();

        load().forEach(this::safeAdd);
    }

    public Optional<GameProfile> getProfileByNameNow(@NotNull String name) {
        var lowercaseName = name.toLowerCase();
        var gameProfileInfo = this.profilesByName.get(lowercaseName);
        if (gameProfileInfo == null) return Optional.empty();

        return Optional.of(gameProfileInfo.getProfile());
    }

    public Optional<GameProfile> getProfileByName(@NotNull String name) {
        var lowercaseName = name.toLowerCase();

        var gameProfileInfo = this.profilesByName.get(lowercaseName);

        boolean save = false;
        if (gameProfileInfo != null && (new Date()).getTime() >= gameProfileInfo.expirationDate.getTime()) {
            this.profilesByUUID.remove(gameProfileInfo.getProfile().getId());
            this.profilesByName.remove(gameProfileInfo.getProfile().getName().toLowerCase(Locale.ROOT));
            save = true;
            gameProfileInfo = null;
        }

        Optional<GameProfile> gameProfile;
        if (gameProfileInfo != null) {
            gameProfileInfo.setLastAccess(this.getNextOperation());
            gameProfile = Optional.of(gameProfileInfo.getProfile());
        } else {
            gameProfile = lookupGameProfile(this.profileRepository, lowercaseName);
            if (gameProfile.isPresent()) {
                this.add(gameProfile.get());
                save = false;
            }
        }

        if (save) this.save();

        return gameProfile;
    }

    public void add(GameProfile profile) {
        var calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.MONTH, 1);
        var date = calendar.getTime();

        var gameProfileInfo = new GameProfileInfo(profile, date);
        this.safeAdd(gameProfileInfo);
        this.save();
    }

    private void safeAdd(GameProfileInfo entry) {
        var gameProfile = entry.getProfile();
        entry.setLastAccess(this.getNextOperation());

        var name = gameProfile.getName();
        if (name != null) {
            this.profilesByName.put(name.toLowerCase(Locale.ROOT), entry);
        }

        var uuid = gameProfile.getId();
        if (uuid != null) {
            this.profilesByUUID.put(uuid, entry);
        }

    }

    private long getNextOperation() {
        return this.operationCount.incrementAndGet();
    }

    private static Optional<GameProfile> lookupGameProfile(GameProfileRepository repository, String name) {
        FiguraMod.LOGGER.info("Lookup {}", name);

        final AtomicReference<GameProfile> gameProfileRef = new AtomicReference<>();

        var profileLookupCallback = new ProfileLookupCallback() {
            public void onProfileLookupSucceeded(GameProfile gameProfile) {
                gameProfileRef.set(gameProfile);
            }

            public void onProfileLookupFailed(GameProfile gameProfile, Exception exception) {
                gameProfileRef.set(null);
            }
        };
        repository.findProfilesByNames(new String[]{name}, Agent.MINECRAFT, profileLookupCallback);
        var gameProfile = gameProfileRef.get();

        if (gameProfile == null) {
            UUID uuid = UUIDUtil.getOrCreatePlayerUUID(new GameProfile(null, name));
            return Optional.of(new GameProfile(uuid, name));
        } else {
            return Optional.of(gameProfile);
        }
    }

    private List<GameProfileInfo> load() {
        try {
            var reader = Files.newReader(cacheFile, StandardCharsets.UTF_8);
            List<GameProfileInfo> list = gson.fromJson(reader, GameProfileInfo.LIST_TYPE);
            if (list != null) return list;
        } catch (FileNotFoundException ignored) {
        } catch (JsonParseException e) {
            FiguraMod.LOGGER.warn("Failed to load profile cache {}", cacheFile, e);
        }

        return Collections.emptyList();
    }

    private CompletableFuture<Void> save() {
        return CompletableFuture.supplyAsync(() -> {
            var gameProfiles = getTopMRUProfiles(1000);

            try (var writer = Files.newWriter(cacheFile, StandardCharsets.UTF_8)) {
                writer.write(gson.toJson(gameProfiles, GameProfileInfo.LIST_TYPE));
            } catch (IOException e) {
                FiguraMod.LOGGER.warn("Failed to save profile cache {}", cacheFile, e);
            }

            return null;
        });
    }

    private List<GameProfileInfo> getTopMRUProfiles(int limit) {
        return ImmutableList.copyOf(this.profilesByUUID.values())
                .stream()
                .sorted(Comparator.comparing(GameProfileInfo::getLastAccess).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private static class GameProfileInfo {

        private static Type LIST_TYPE = new TypeToken<ArrayList<GameProfileInfo>>() {}.getType();

        private final GameProfile profile;
        final Date expirationDate;
        private volatile long lastAccess;

        private GameProfileInfo(GameProfile profile, Date expirationDate) {
            this.profile = profile;
            this.expirationDate = expirationDate;
        }

        public GameProfile getProfile() {
            return this.profile;
        }

        public Date getExpirationDate() {
            return this.expirationDate;
        }

        public void setLastAccess(long lastAccessed) {
            this.lastAccess = lastAccessed;
        }

        public long getLastAccess() {
            return this.lastAccess;
        }

        private static class Serializer implements JsonSerializer<GameProfileInfo>, JsonDeserializer<GameProfileInfo> {

            private static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

            @Override
            public JsonElement serialize(GameProfileInfo src, Type typeOfSrc, JsonSerializationContext context) {
                var jsonObject = new JsonObject();

                jsonObject.addProperty("name", src.getProfile().getName());
                var profileId = src.profile.getId();
                jsonObject.addProperty("uuid", profileId == null ? "" : profileId.toString());
                jsonObject.addProperty("expiresOn", DATE_FORMAT.format(src.getExpirationDate()));

                return jsonObject;
            }

            @Override
            public GameProfileInfo deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                if (!json.isJsonObject()) throw new JsonParseException("Not an json object");

                var jsonObject = json.getAsJsonObject();
                var nameObject = jsonObject.get("name");
                var uuidObject = jsonObject.get("uuid");
                var expiresOnObject = jsonObject.get("expiresOn");

                if (nameObject == null || uuidObject == null || expiresOnObject == null)
                    throw new JsonParseException("Bad GameProfileInfo");

                var name = nameObject.getAsString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidObject.getAsString());
                } catch (IllegalArgumentException e) {
                    throw new JsonParseException(e);
                }

                Date expiresOn;
                try {
                    expiresOn = DATE_FORMAT.parse(expiresOnObject.getAsString());
                } catch (ParseException e) {
                    throw new JsonParseException(e);
                }

                return new GameProfileInfo(new GameProfile(uuid, name), expiresOn);
            }
        }
    }
}
