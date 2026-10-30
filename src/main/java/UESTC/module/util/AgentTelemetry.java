package UESTC.module.util;

import adf.core.agent.action.Action;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Structured, best-effort diagnostics; never used as a decision input. */
public final class AgentTelemetry {
    // Values contain no references to AgentInfo, so a finished simulation can be collected.
    private static final Map<AgentInfo, Frame> FRAMES = new WeakHashMap<>();
    private static final int DEDUP_LIMIT = 1024;
    private static boolean errorReported;

    private AgentTelemetry() { }

    private static final class Frame {
        final int time;
        final Set<String> payloads = new LinkedHashSet<>();
        Frame(int time) { this.time = time; }
    }

    public static void decision(AgentInfo ai, WorldInfo wi, String module, EntityID target, Object... fields) {
        write(ai, "decision", module, data -> {
            data.put("position", id(safe(ai::getPosition)));
            data.put("target", id(target));
            StandardEntity entity = target == null || wi == null ? null : safe(() -> wi.getEntity(target));
            if (entity != null) {
                data.put("target_type", entity.getStandardURN().name());
                if (entity instanceof Human) human(data, "target_", (Human) entity);
            }
            data.put("target_distance", distance(wi, safe(ai::getID), target));
            fields(data, fields);
        });
    }

    public static void assignments(AgentInfo ai, WorldInfo wi, String module, Map<EntityID, EntityID> assignments) {
        write(ai, "assignments", module, data -> {
            Map<Integer, Integer> targets = new TreeMap<>();
            if (assignments != null) {
                for (Map.Entry<EntityID, EntityID> entry : assignments.entrySet()) {
                    if (entry.getKey() != null) targets.put(id(entry.getKey()), id(entry.getValue()));
                }
            }
            data.put("assignment_count", targets.size());
            data.put("assignments", targets);
        });
    }

    public static void tick(AgentInfo ai, WorldInfo wi) {
        write(ai, "tick", "Agent", data -> {
            data.put("position", id(safe(ai::getPosition)));
            data.put("x", safe(ai::getX));
            data.put("y", safe(ai::getY));
            StandardEntity self = safe(ai::me);
            if (self instanceof Human) human(data, "", (Human) self);
            if (self instanceof FireBrigade) data.put("water", safe(((FireBrigade) self)::getWater));
            int actionTime = ai.getTime() - 1;
            Action action = safe(() -> ai.getExecutedAction(actionTime));
            data.put("action_time", actionTime);
            data.put("executed_action_type", action == null ? null : action.getClass().getSimpleName());
            data.put("executed_action", action == null ? null : safe(action::toString));
        });
    }

    public static void event(AgentInfo ai, String event, String module, Object... fields) {
        write(ai, event, module, data -> fields(data, fields));
    }

    public static void path(AgentInfo ai, WorldInfo wi, String module, EntityID from, EntityID target,
                            List<EntityID> path, long elapsedNanos) {
        write(ai, "path", module, data -> {
            data.put("from", id(from));
            data.put("target", id(target));
            boolean found = path != null && !path.isEmpty();
            data.put("found", found);
            data.put("path_length", path == null ? 0 : path.size());
            data.put("path_nodes", raw(path));
            Long total = found ? 0L : null;
            EntityID previous = from;
            if (found) {
                for (EntityID next : path) {
                    if (previous != null && !previous.equals(next)) {
                        Integer step = distance(wi, previous, next);
                        if (step == null) { total = null; break; }
                        total += step;
                    }
                    previous = next;
                }
            }
            data.put("distance", total);
            data.put("duration_ms", Math.max(0L, elapsedNanos) / 1_000_000.0);
        });
    }

    private static void human(Map<String, Object> data, String prefix, Human human) {
        data.put(prefix + "position", id(safe(human::getPosition)));
        data.put(prefix + "hp", safe(human::getHP));
        data.put(prefix + "damage", safe(human::getDamage));
        data.put(prefix + "buriedness", safe(human::getBuriedness));
    }

    private static Integer distance(WorldInfo wi, EntityID from, EntityID to) {
        if (wi == null || from == null || to == null) return null;
        Integer value = safe(() -> wi.getDistance(from, to));
        return value == null || value < 0 ? null : value;
    }

    private static Integer id(EntityID entity) { return entity == null ? null : entity.getValue(); }

    private static <T> T safe(Supplier<T> supplier) {
        try { return supplier.get(); } catch (RuntimeException ignored) { return null; }
    }

    private static void fields(Map<String, Object> data, Object... fields) {
        if (fields == null) return;
        for (int index = 0; index + 1 < fields.length; index += 2) {
            String key = String.valueOf(fields[index]);
            if (!data.containsKey(key)) data.put(key, raw(fields[index + 1]));
        }
    }

    // Keep entity IDs numeric and serialize containers as data, never framework objects.
    private static Object raw(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof EntityID) return id((EntityID) value);
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(raw(entry.getKey())), raw(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable<?> || value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            if (value instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) value) result.add(raw(item));
            } else {
                for (int i = 0; i < Array.getLength(value); i++) result.add(raw(Array.get(value, i)));
            }
            return result;
        }
        return String.valueOf(value);
    }

    private static void write(AgentInfo ai, String event, String module, Consumer<Map<String, Object>> fill) {
        try {
            if (ai == null || ai.me() == null) return;
            Logger logger = DefaultLogger.getLogger(ai.me());
            if (logger == null || !logger.isInfoEnabled()) {
                throw new IllegalStateException("Agent INFO logger unavailable or disabled");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            int time = ai.getTime();
            data.put("schema", 1);
            data.put("time", time);
            data.put("agent", id(ai.getID()));
            data.put("type", ai.me().getStandardURN().name());
            data.put("event", event);
            data.put("module", module);
            fill.accept(data);
            String payload = encode(data);
            String fingerprint = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
            synchronized (FRAMES) {
                Frame frame = FRAMES.get(ai);
                if (frame == null || frame.time != time) {
                    frame = new Frame(time);
                    FRAMES.put(ai, frame);
                }
                if (!frame.payloads.add(fingerprint)) return;
                if (frame.payloads.size() > DEDUP_LIMIT) {
                    frame.payloads.remove(frame.payloads.iterator().next());
                }
            }
            logger.info("[Telemetry] " + payload);
        } catch (Exception failure) {
            // Undefined simulator properties and logger/serialization failures must not change decisions.
            reportFailure(failure);
        }
    }

    private static synchronized void reportFailure(Exception failure) {
        if (errorReported) return;
        errorReported = true;
        try {
            System.err.println("[TelemetryError] " + failure.getClass().getSimpleName() + ": "
                + String.valueOf(failure.getMessage()).replace('\n', ' ').replace('\r', ' '));
        } catch (Exception ignored) { }
    }

    private static String encode(Object value) {
        StringBuilder json = new StringBuilder(512);
        appendJson(json, value);
        return json.toString();
    }

    private static void appendJson(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Number) {
            boolean nonFinite = (value instanceof Double || value instanceof Float)
                && !Double.isFinite(((Number) value).doubleValue());
            json.append(nonFinite ? "null" : value.toString());
        } else if (value instanceof Map<?, ?>) {
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) json.append(',');
                first = false;
                quote(json, String.valueOf(entry.getKey()));
                json.append(':');
                appendJson(json, entry.getValue());
            }
            json.append('}');
        } else if (value instanceof Iterable<?>) {
            json.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) json.append(',');
                first = false;
                appendJson(json, item);
            }
            json.append(']');
        } else {
            quote(json, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder json, String value) {
        final String hex = "0123456789abcdef";
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '"' || ch == '\\') {
                json.append('\\').append(ch);
            } else if (ch < 0x20) {
                json.append("\\u00").append(hex.charAt(ch >>> 4)).append(hex.charAt(ch & 15));
            } else {
                json.append(ch);
            }
        }
        json.append('"');
    }
}
