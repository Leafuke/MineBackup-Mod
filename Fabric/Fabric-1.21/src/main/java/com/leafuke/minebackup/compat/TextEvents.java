package com.leafuke.minebackup.compat;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Locale;

/**
 * Creates text events across the 1.21 text API transition.
 *
 * <p>Minecraft 1.21 exposes {@code ClickEvent} and {@code HoverEvent} as
 * classes with legacy constructors. Minecraft 1.21.5 and later expose the
 * same intermediary types as interfaces and use event-specific records.
 * Reflection is deliberately kept in this boundary so the generic 1.21
 * artifact does not emit a constructor invocation that is absent on 1.21.8.
 */
public final class TextEvents {
    private TextEvents() {
    }

    public static ClickEvent runCommand(String command) {
        return createClickEvent(
                "RUN_COMMAND",
                String.class,
                command,
                String.class,
                command);
    }

    public static ClickEvent suggestCommand(String command) {
        return createClickEvent(
                "SUGGEST_COMMAND",
                String.class,
                command,
                String.class,
                command);
    }

    public static ClickEvent openUrl(URI uri) {
        return createClickEvent(
                "OPEN_URL",
                String.class,
                uri.toString(),
                URI.class,
                uri);
    }

    public static HoverEvent showText(Text text) {
        try {
            Class<?> eventType = HoverEvent.class;
            Class<?> actionType = findActionType(eventType);
            Object action = findAction(actionType, "SHOW_TEXT");
            if (!eventType.isInterface()) {
                return (HoverEvent) eventType
                        .getConstructor(actionType, Object.class)
                        .newInstance(action, text);
            }

            for (Class<?> implementation : eventType.getDeclaredClasses()) {
                if (!eventType.isAssignableFrom(implementation)) {
                    continue;
                }
                Constructor<?> constructor;
                try {
                    constructor = implementation.getConstructor(Text.class);
                } catch (NoSuchMethodException exception) {
                    continue;
                }
                Object candidate = constructor.newInstance(text);
                Method getAction = findActionMethod(implementation, actionType);
                if (action.equals(getAction.invoke(candidate))) {
                    return (HoverEvent) candidate;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The caller can still display a plain, usable text message.
        }
        return null;
    }

    private static ClickEvent createClickEvent(
            String actionName,
            Class<?> legacyValueType,
            Object legacyValue,
            Class<?> modernValueType,
            Object modernValue) {
        try {
            Class<?> eventType = ClickEvent.class;
            Class<?> actionType = findActionType(eventType);
            Object action = findAction(actionType, actionName);
            if (!eventType.isInterface()) {
                return (ClickEvent) eventType
                        .getConstructor(actionType, legacyValueType)
                        .newInstance(action, legacyValue);
            }

            for (Class<?> implementation : eventType.getDeclaredClasses()) {
                if (!eventType.isAssignableFrom(implementation)) {
                    continue;
                }
                Constructor<?> constructor;
                try {
                    constructor = implementation.getConstructor(modernValueType);
                } catch (NoSuchMethodException exception) {
                    continue;
                }
                Object candidate = constructor.newInstance(modernValue);
                Method getAction = findActionMethod(implementation, actionType);
                if (action.equals(getAction.invoke(candidate))) {
                    return (ClickEvent) candidate;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The caller can still display a plain, usable text message.
        }
        return null;
    }

    private static Class<?> findActionType(Class<?> eventType) throws NoSuchMethodException {
        for (Class<?> nested : eventType.getDeclaredClasses()) {
            if (nested.isEnum()) {
                return nested;
            }
        }
        throw new NoSuchMethodException("No text event action enum found");
    }

    private static Object findAction(Class<?> actionType, String name)
            throws ReflectiveOperationException {
        Object constants = actionType.getEnumConstants();
        if (constants instanceof Object[] values) {
            for (Object value : values) {
                if (actionMatches(value, name)) {
                    return value;
                }
            }
        }
        throw new NoSuchFieldException(actionType.getName() + "." + name);
    }

    private static boolean actionMatches(Object value, String name)
            throws ReflectiveOperationException {
        if (value instanceof Enum<?> constant && constant.name().equals(name)) {
            return true;
        }

        String serializedName = name.toLowerCase(Locale.ROOT);
        for (Method method : value.getClass().getMethods()) {
            if (method.getParameterCount() != 0
                    || method.getReturnType() != String.class
                    || method.getDeclaringClass() == Object.class) {
                continue;
            }
            Object result = method.invoke(value);
            if (serializedName.equals(result) || name.equals(result)) {
                return true;
            }
        }
        return false;
    }

    private static Method findActionMethod(Class<?> implementation, Class<?> actionType)
            throws NoSuchMethodException {
        for (Method method : implementation.getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == actionType) {
                return method;
            }
        }
        throw new NoSuchMethodException("No text event action method found");
    }
}
