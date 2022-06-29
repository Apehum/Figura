package org.moon.figura.backend;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.ClientTelemetryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.moon.figura.FiguraMod;
import org.moon.figura.avatars.Avatar;
import org.moon.figura.avatars.AvatarManager;
import org.moon.figura.config.Config;
import org.moon.figura.utils.FiguraFuture;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

public class NetworkManager {

    public static final int AUTH_PORT = 25565;
    public static final int BACKEND_PORT = 25500;
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int RECONNECT = 6000; //5 min

    protected static final LinkedList<DownloadRequest> REQUEST_QUEUE = new LinkedList<>();

    public static int backendStatus = 1;
    public static String disconnectedReason;
    public static boolean banned = false;
    private static int lastAuth = 0;
    protected static String authToken;

    private static Connection authConnection;
    protected static WebsocketManager backend;

    private static final FiguraFuture FUTURE = new FiguraFuture();

    // -- methods -- //

    public static void tick() {
        //auth ticking
        if (authConnection != null) {
            if (authConnection.isConnected())
                authConnection.tick();
            else {
                authConnection.handleDisconnection();
                authConnection = null;
            }
        }

        //backend tick
        if (hasBackend())
            backend.tick();

        //re auth
        lastAuth++;
        if (lastAuth >= RECONNECT)
            assertBackend();
    }

    // -- functions -- //

    public static void auth(boolean force) {
        FUTURE.run(() -> {
            try {
                lastAuth = (int) (Math.random() * 300) - 150; //between -15 and +15 seconds

                if (!force && authToken != null || banned)
                    return;

                if (authConnection != null && !authConnection.isConnected())
                    authConnection.handleDisconnection();

                FiguraMod.LOGGER.info("Authenticating with " + FiguraMod.MOD_NAME + " server...");
                backendStatus = 2;

                Minecraft minecraft = Minecraft.getInstance();
                ClientTelemetryManager telemetryManager = minecraft.createTelemetryManager();
                InetSocketAddress inetSocketAddress = new InetSocketAddress((String) Config.BACKEND.value, AUTH_PORT);

                Connection connection = Connection.connectToServer(inetSocketAddress, minecraft.options.useNativeTransport());
                connection.setListener(new ClientHandshakePacketListenerImpl(connection, minecraft, null, (text) -> FiguraMod.LOGGER.info(text.getString())) {
                    @Override
                    public void handleGameProfile(ClientboundGameProfilePacket clientboundGameProfilePacket) {
                        super.handleGameProfile(clientboundGameProfilePacket);
                        connection.setListener(new ClientPacketListener(minecraft, null, connection, clientboundGameProfilePacket.getGameProfile(), telemetryManager) {
                            @Override
                            public void onDisconnect(Component reason) {
                                telemetryManager.onDisconnect();
                                authConnection = null;
                                disconnectedReason = reason.getString();

                                //parse token
                                String[] split = disconnectedReason.split("<", 2);
                                if (split.length < 2) {
                                    backendStatus = 1;
                                    return;
                                }

                                split = split[1].split(">", 2);
                                if (split.length < 2) {
                                    backendStatus = 1;
                                    return;
                                }

                                JsonObject token = new JsonObject();
                                token.addProperty("type", "auth");
                                token.addProperty("token", split[0]);

                                MessageHandler.handleMessage(GSON.toJson(token));
                            }
                        });
                    }

                    @Override
                    public void onDisconnect(Component reason) {
                        authConnection = null;
                        backendStatus = 1;
                        disconnectedReason = reason.getString();
                    }
                });

                connection.send(new ClientIntentionPacket(inetSocketAddress.getHostName(), inetSocketAddress.getPort(), ConnectionProtocol.LOGIN));
                connection.send(new ServerboundHelloPacket(minecraft.getUser().getName(), minecraft.getProfileKeyPairManager().profilePublicKeyData()));

                authConnection = connection;
            } catch (Exception e) {
                authConnection = null;
                backendStatus = 1;
                disconnectedReason = e.getMessage();
            }
        });
    }

    public static void closeBackend() {
        FUTURE.run(() -> {
            if (backend == null)
                return;

            if (!backend.isOpen()) {
                backend = null;
                return;
            }

            backend.close();
            backend = null;
        });
    }

    public static void openBackend() {
        auth(false);
        FUTURE.run(() -> {
            if (NetworkManager.authToken == null || hasBackend())
                return;

            backend = new WebsocketManager();
            backend.connect();
        });
    }

    public static void assertBackend() {
        FUTURE.run(() -> {
            if (!hasBackend())
                openBackend();
        });
    }

    public static boolean hasBackend() {
        return backend != null && backend.isOpen();
    }

    public static void sendMessage(String message) {
        if (hasBackend()) backend.send(message);
    }

    public static void clearRequests() {
        REQUEST_QUEUE.clear();
    }

    public static void clearRequestsFor(UUID id) {
        REQUEST_QUEUE.removeIf(request -> request.id.equals(id));
    }

    public static boolean canUpload() {
        return hasBackend() && backend.upload.peek();
    }

    // -- avatar management -- //

    public static void uploadAvatar(Avatar avatar, UUID id) {
        if (hasBackend())
            backend.upload.use();

        assertBackend();
        FUTURE.run(() -> {
            if (avatar == null || !hasBackend())
                return;

            JsonObject json = new JsonObject();
            json.addProperty("type", "upload");
            json.addProperty("id", id == null ? "avatar" : id.toString()); //todo - change to a random UUID when the multiple avatar system is done

            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.writeCompressed(avatar.nbt, baos);

                String bytes = Base64.getEncoder().encodeToString(baos.toByteArray());
                json.addProperty("data", bytes);
            } catch (Exception e) {
                FiguraMod.LOGGER.error("Failed to upload avatar!", e);
                return;
            }

            sendMessage(GSON.toJson(json));
            AvatarManager.localUploaded = true;
        });
    }

    public static void getAvatar(UUID id) { //TODO - replace "avatar"
        String avatarID = "avatar";

        DownloadRequest.AvatarRequest request = new DownloadRequest.AvatarRequest(id, avatarID);
        REQUEST_QUEUE.remove(request);
        REQUEST_QUEUE.add(request);
    }

    // -- backend command -- //

    public static LiteralArgumentBuilder<FabricClientCommandSource> getCommand() {
        //root
        LiteralArgumentBuilder<FabricClientCommandSource> backend = LiteralArgumentBuilder.literal("backend");

        //force backend connection
        LiteralArgumentBuilder<FabricClientCommandSource> connect = LiteralArgumentBuilder.literal("connect");
        connect.executes(context -> {
            NetworkManager.auth(true);
            return 1;
        });

        //token
        RequiredArgumentBuilder<FabricClientCommandSource, String> token = RequiredArgumentBuilder.argument("token", StringArgumentType.word());
        token.executes(context -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", "auth");
            json.addProperty("token", StringArgumentType.getString(context, "token"));
            MessageHandler.handleMessage(GSON.toJson(json));
            return 1;
        });

        //add arguments
        connect.then(token);

        //message sender

        //root
        LiteralArgumentBuilder<FabricClientCommandSource> message = LiteralArgumentBuilder.literal("message");

        //type argument
        RequiredArgumentBuilder<FabricClientCommandSource, String> messageType = RequiredArgumentBuilder.argument("messageType", StringArgumentType.word());
        messageType.executes(context -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", StringArgumentType.getString(context, "messageType"));
            sendMessage(GSON.toJson(json));
            return 1;
        });

        //value argument
        RequiredArgumentBuilder<FabricClientCommandSource, String> value = RequiredArgumentBuilder.argument("value", StringArgumentType.greedyString());
        value.executes(context -> {
            String t = StringArgumentType.getString(context, "messageType");
            String v = StringArgumentType.getString(context, "value");

            JsonObject json = new JsonObject();
            json.addProperty("type", t);

            JsonObject object = JsonParser.parseString(v).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
                json.add(entry.getKey(), entry.getValue());

            sendMessage(GSON.toJson(json));
            return 1;
        });

        //add arguments
        messageType.then(value);
        message.then(messageType);

        //add commands to root
        backend.then(connect);
        backend.then(message);

        return backend;
    }
}
