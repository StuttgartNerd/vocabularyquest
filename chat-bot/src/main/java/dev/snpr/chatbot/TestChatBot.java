package dev.snpr.chatbot;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public final class TestChatBot {
    private static final Random RANDOM = new Random();

    private TestChatBot() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);

        System.out.println("Connecting bot to " + config.host + ":" + config.port + " as " + config.username);

        MinecraftProtocol protocol = new MinecraftProtocol(config.username);
        ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                .setAddress(config.host, config.port)
                .setProtocol(protocol)
                .create();

        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                handlePacket(session, packet, config);
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                System.err.println("Disconnected: " + event.getReason());
                if (event.getCause() != null) {
                    event.getCause().printStackTrace(System.err);
                }
            }

            @Override
            public void packetError(PacketErrorEvent event) {
                System.err.println("Packet error for " + event.getPacketClass() + ": " + event.getCause());
            }
        });

        session.connect(true);
        System.out.println("Connected. Type messages and press Enter to send chat. Type /quit to exit.");

        if (config.sendOnConnect != null && !config.sendOnConnect.isBlank()) {
            if (config.sendDelayMillis > 0L) {
                Thread.sleep(config.sendDelayMillis);
            }
            sendInput(session, config.sendOnConnect);
        }

        if (config.noStdin) {
            waitWithoutStdin(session, config.holdSeconds);
        } else {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (session.isConnected()) {
                    if (!reader.ready()) {
                        Thread.sleep(50L);
                        continue;
                    }

                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }

                    if (line.equalsIgnoreCase("/quit")) {
                        break;
                    }

                    if (line.isBlank()) {
                        continue;
                    }

                    sendInput(session, line);
                }
            }
        }

        if (session.isConnected()) {
            session.disconnect("Bye");
        }
    }

    private static void waitWithoutStdin(Session session, long holdSeconds) throws InterruptedException {
        long holdMillis = holdSeconds > 0L ? holdSeconds * 1_000L : 1_500L;
        long deadline = System.currentTimeMillis() + holdMillis;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
    }

    private static void handlePacket(ClientNetworkSession session, Packet packet, Config config) {
        String className = packet.getClass().getSimpleName();
        if (!className.contains("Chat")) {
            return;
        }

        String text = extractChatText(packet);
        System.out.println("[CHAT] " + text);

        if (config.autoReply && text.toLowerCase(Locale.ROOT).contains(config.trigger.toLowerCase(Locale.ROOT))) {
            sendChat(session, config.reply);
        }
    }

    private static void sendInput(Session session, String input) {
        if (input.startsWith("/") && input.length() > 1) {
            sendCommand(session, input.substring(1).trim());
            return;
        }

        sendChat(session, input);
    }

    private static String extractChatText(Packet packet) {
        List<String> methodNames = Arrays.asList(
                "content",
                "message",
                "text",
                "getContent",
                "getMessage",
                "getText"
        );

        for (String methodName : methodNames) {
            try {
                Method method = packet.getClass().getMethod(methodName);
                Object value = method.invoke(packet);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return packet.toString();
    }

    private static void sendChat(Session session, String message) {
        Packet packet = buildServerboundChatPacket(message);
        if (packet == null) {
            System.err.println("Could not create ServerboundChatPacket for this MCProtocolLib version.");
            return;
        }

        session.send(packet);
    }

    private static void sendCommand(Session session, String command) {
        Packet packet = buildServerboundCommandPacket(command);
        if (packet == null) {
            System.err.println("Could not create ServerboundChatCommandPacket for this MCProtocolLib version.");
            return;
        }

        session.send(packet);
    }

    private static Packet buildServerboundChatPacket(String message) {
        try {
            Class<?> packetClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket"
            );

            Constructor<?>[] constructors = packetClass.getConstructors();
            Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

            for (Constructor<?> constructor : constructors) {
                Object[] args = buildArgsForConstructor(constructor, message);
                if (args == null) {
                    continue;
                }

                try {
                    Object packet = constructor.newInstance(args);
                    if (packet instanceof Packet p) {
                        return p;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        } catch (ClassNotFoundException ignored) {
        }

        return null;
    }

    private static Packet buildServerboundCommandPacket(String command) {
        try {
            Class<?> packetClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket"
            );

            Constructor<?> constructor = packetClass.getConstructor(String.class);
            Object packet = constructor.newInstance(command);
            if (packet instanceof Packet p) {
                return p;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        return null;
    }

    private static Object[] buildArgsForConstructor(Constructor<?> constructor, String message) {
        Class<?>[] types = constructor.getParameterTypes();
        List<Object> values = new ArrayList<>(types.length);
        int longCount = 0;

        for (Class<?> type : types) {
            if (type == String.class) {
                values.add(message);
                continue;
            }

            if (type == long.class || type == Long.class) {
                values.add(longCount++ == 0 ? Instant.now().toEpochMilli() : RANDOM.nextLong());
                continue;
            }

            if (type == int.class || type == Integer.class) {
                values.add(0);
                continue;
            }

            if (type == boolean.class || type == Boolean.class) {
                values.add(false);
                continue;
            }

            if (type == byte.class || type == Byte.class) {
                values.add((byte) 0);
                continue;
            }

            if (type == short.class || type == Short.class) {
                values.add((short) 0);
                continue;
            }

            if (type == float.class || type == Float.class) {
                values.add(0.0f);
                continue;
            }

            if (type == double.class || type == Double.class) {
                values.add(0.0d);
                continue;
            }

            if (type == char.class || type == Character.class) {
                values.add('\0');
                continue;
            }

            if (type == BitSet.class) {
                values.add(new BitSet());
                continue;
            }

            if (type == UUID.class) {
                values.add(UUID.randomUUID());
                continue;
            }

            if (type == Instant.class) {
                values.add(Instant.now());
                continue;
            }

            if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                if (constants == null || constants.length == 0) {
                    return null;
                }

                values.add(constants[0]);
                continue;
            }

            if (type.isArray()) {
                values.add(null);
                continue;
            }

            if (!type.isPrimitive()) {
                values.add(null);
                continue;
            }

            return null;
        }

        return values.toArray(new Object[0]);
    }

    private static final class Config {
        private final String host;
        private final int port;
        private final String username;
        private final boolean autoReply;
        private final String trigger;
        private final String reply;
        private final boolean noStdin;
        private final String sendOnConnect;
        private final long sendDelayMillis;
        private final long holdSeconds;

        private Config(
                String host,
                int port,
                String username,
                boolean autoReply,
                String trigger,
                String reply,
                boolean noStdin,
                String sendOnConnect,
                long sendDelayMillis,
                long holdSeconds
        ) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.autoReply = autoReply;
            this.trigger = trigger;
            this.reply = reply;
            this.noStdin = noStdin;
            this.sendOnConnect = sendOnConnect;
            this.sendDelayMillis = sendDelayMillis;
            this.holdSeconds = holdSeconds;
        }

        private static Config fromArgs(String[] args) {
            String host = envOrDefault("MC_HOST", "127.0.0.1");
            int port = intEnvOrDefault("MC_PORT", 25565);
            String username = envOrDefault("MC_USERNAME", "ChatBot");
            boolean autoReply = boolEnvOrDefault("MC_AUTOREPLY", true);
            String trigger = envOrDefault("MC_TRIGGER", "!hello-bot");
            String reply = envOrDefault("MC_REPLY", "Hello from MCProtocolLib bot.");
            boolean noStdin = boolEnvOrDefault("MC_NO_STDIN", false);
            String sendOnConnect = envOrDefault("MC_SEND_ON_CONNECT", "");
            long sendDelayMillis = longEnvOrDefault("MC_SEND_DELAY_MS", 0L);
            long holdSeconds = longEnvOrDefault("MC_HOLD_SECONDS", 0L);

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--host".equals(arg) && i + 1 < args.length) {
                    host = args[++i];
                } else if ("--port".equals(arg) && i + 1 < args.length) {
                    port = Integer.parseInt(args[++i]);
                } else if ("--username".equals(arg) && i + 1 < args.length) {
                    username = args[++i];
                } else if ("--trigger".equals(arg) && i + 1 < args.length) {
                    trigger = args[++i];
                } else if ("--reply".equals(arg) && i + 1 < args.length) {
                    reply = args[++i];
                } else if ("--no-autoreply".equals(arg)) {
                    autoReply = false;
                } else if ("--no-stdin".equals(arg)) {
                    noStdin = true;
                } else if ("--send-on-connect".equals(arg) && i + 1 < args.length) {
                    sendOnConnect = args[++i];
                } else if ("--send-delay-ms".equals(arg) && i + 1 < args.length) {
                    sendDelayMillis = Long.parseLong(args[++i]);
                } else if ("--hold-seconds".equals(arg) && i + 1 < args.length) {
                    holdSeconds = Long.parseLong(args[++i]);
                }
            }

            if (sendOnConnect != null && sendOnConnect.isBlank()) {
                sendOnConnect = null;
            }

            return new Config(
                    host,
                    port,
                    username,
                    autoReply,
                    trigger,
                    reply,
                    noStdin,
                    sendOnConnect,
                    sendDelayMillis,
                    holdSeconds
            );
        }

        private static String envOrDefault(String key, String defaultValue) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static int intEnvOrDefault(String key, int defaultValue) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        private static long longEnvOrDefault(String key, long defaultValue) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        private static boolean boolEnvOrDefault(String key, boolean defaultValue) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            return "1".equals(value)
                    || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value)
                    || "on".equalsIgnoreCase(value);
        }
    }
}
