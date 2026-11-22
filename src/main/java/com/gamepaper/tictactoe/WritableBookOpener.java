package com.gamepaper.tictactoe;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class WritableBookOpener {

    private WritableBookOpener() {
    }

    static boolean openMainHand(Player player) {
        try {
            ClassLoader loader = player.getClass().getClassLoader();
            Class<?> handClass = Class.forName(
                    "net.minecraft.world.InteractionHand", true, loader);
            Class<?> packetClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundOpenBookPacket", true, loader);

            @SuppressWarnings({"rawtypes", "unchecked"})
            Object mainHand = Enum.valueOf((Class<? extends Enum>) handClass, "MAIN_HAND");
            Constructor<?> constructor = packetClass.getConstructor(handClass);
            Object packet = constructor.newInstance(mainHand);

            Method getHandle = player.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(player);
            Object connection = findConnection(serverPlayer);
            if (connection == null) {
                return false;
            }

            for (Method method : connection.getClass().getMethods()) {
                if (!method.getName().equals("send") || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(packetClass)) {
                    continue;
                }
                method.invoke(connection, packet);
                return true;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static Object findConnection(Object serverPlayer) throws IllegalAccessException {
        Class<?> current = serverPlayer.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getName().equals("connection")
                        && !field.getType().getName().contains("GamePacketListener")) {
                    continue;
                }
                if (!field.canAccess(serverPlayer)) {
                    field.setAccessible(true);
                }
                Object value = field.get(serverPlayer);
                if (value != null) {
                    return value;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
