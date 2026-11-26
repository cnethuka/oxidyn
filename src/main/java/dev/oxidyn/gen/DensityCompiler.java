package dev.oxidyn.gen;

import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.noise.NativeNoiseHolder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flattens a seeded NoiseRouter into the word stream native/src/gen/program.rs
 * parses. Children are always written before their parents.
 *
 * <p>Most density function types are package-private records, so their
 * fields are read through the record accessors by reflection. This only runs
 * once per dimension, so speed doesn't matter here.
 *
 * <p>Anything we don't recognise (a mod's custom density function, End
 * islands for now) throws {@link Unsupported} and that dimension keeps
 * using vanilla's fill.
 */
final class DensityCompiler {
    static final class Unsupported extends RuntimeException {
        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    private static final String DF = "net.minecraft.world.level.levelgen.DensityFunctions$";
    private static final Map<Class<?>, Map<String, Method>> ACCESSORS = new ConcurrentHashMap<>();

    private final NativeBackend backend;
    private final LongArrayList words = new LongArrayList();
    // keyed by equals() like NoiseChunk.wrap, so equal markers share a slot
    private final Map<DensityFunction, Integer> nodes = new HashMap<>();
    private final Map<CubicSpline<?, ?>, Integer> splines = new IdentityHashMap<>();
    /** noise descriptors the native program points into; must stay reachable */
    final List<Object> keepAlive = new ArrayList<>();
    private int nodeCount;
    private int splineCount;

    DensityCompiler(NativeBackend backend) {
        this.backend = backend;
    }

    long[] compile(NoiseRouter router) {
        DensityFunction[] roots = {
                router.finalDensity(), router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.erosion(), router.depth(),
                router.initialDensityWithoutJaggedness(), router.veinToggle(), router.veinRidged(), router.veinGap(),
        };
        int[] ids = new int[roots.length];
        for (int i = 0; i < roots.length; i++) {
            ids[i] = node(roots[i]);
        }
        words.add(200);
        for (int id : ids) {
            words.add(id);
        }
        return words.toLongArray();
    }

    private int node(DensityFunction f) {
        Integer known = nodes.get(f);
        if (known != null) return known;
        int id = emit(f);
        nodes.put(f, id);
        return id;
    }

    private int emit(DensityFunction f) {
        if (f instanceof DensityFunctions.HolderHolder h) {
            // NoiseChunk unwraps these too
            return node(h.function().value());
        }
        if (f instanceof BlendedNoise blended) {
            Object handle = ((NativeNoiseHolder) blended).oxidyn$nativeHandle();
            if (handle == null) throw new Unsupported("blended noise didn't compile");
            keepAlive.add(handle);
            return push(16, backend.noiseAddress(handle));
        }
        if (f instanceof DensityFunctions.Spline s) {
            int spline = spline(s.spline());
            return push(18, spline);
        }

        String type = f.getClass().getName();
        if (!type.startsWith(DF)) throw new Unsupported("unknown density function " + type);
        return switch (type.substring(DF.length())) {
            case "Constant" -> push(0, bits(dbl(f, "value")));
            case "Ap2" -> {
                int a = node(fn(f, "argument1"));
                DensityFunction second = fn(f, "argument2");
                int b = node(second);
                yield switch (enumName(f, "type")) {
                    case "ADD" -> push(1, a, b);
                    case "MUL" -> push(2, a, b);
                    case "MIN" -> push(3, a, b, bits(second.minValue()));
                    case "MAX" -> push(4, a, b, bits(second.maxValue()));
                    default -> throw new Unsupported("ap2 type " + enumName(f, "type"));
                };
            }
            case "MulOrAdd" -> {
                int in = node(fn(f, "input"));
                long c = bits(dbl(f, "argument"));
                yield "ADD".equals(enumName(f, "specificType")) ? push(5, in, c) : push(6, in, c);
            }
            case "Clamp" -> push(7, node(fn(f, "input")), bits(dbl(f, "minValue")), bits(dbl(f, "maxValue")));
            case "Mapped" -> {
                int in = node(fn(f, "input"));
                long kind = switch (enumName(f, "type")) {
                    case "ABS" -> 0;
                    case "SQUARE" -> 1;
                    case "CUBE" -> 2;
                    case "HALF_NEGATIVE" -> 3;
                    case "QUARTER_NEGATIVE" -> 4;
                    case "SQUEEZE" -> 5;
                    default -> throw new Unsupported("mapped type " + enumName(f, "type"));
                };
                yield push(8, in, kind);
            }
            case "RangeChoice" -> {
                int in = node(fn(f, "input"));
                int a = node(fn(f, "whenInRange"));
                int b = node(fn(f, "whenOutOfRange"));
                yield push(9, in, bits(dbl(f, "minInclusive")), bits(dbl(f, "maxExclusive")), a, b);
            }
            case "YClampedGradient" -> push(10, integer(f, "fromY"), integer(f, "toY"),
                    bits(dbl(f, "fromValue")), bits(dbl(f, "toValue")));
            case "Noise" -> push(11, noise(get(f, "noise")), bits(dbl(f, "xzScale")), bits(dbl(f, "yScale")));
            case "ShiftedNoise" -> {
                int sx = node(fn(f, "shiftX"));
                int sy = node(fn(f, "shiftY"));
                int sz = node(fn(f, "shiftZ"));
                yield push(12, sx, sy, sz, bits(dbl(f, "xzScale")), bits(dbl(f, "yScale")), noise(get(f, "noise")));
            }
            case "ShiftA" -> push(13, noise(get(f, "offsetNoise")));
            case "ShiftB" -> push(14, noise(get(f, "offsetNoise")));
            case "Shift" -> push(15, noise(get(f, "offsetNoise")));
            case "WeirdScaledSampler" -> {
                int in = node(fn(f, "input"));
                long kind = switch (enumName(f, "rarityValueMapper")) {
                    case "TYPE1" -> 1;
                    case "TYPE2" -> 2;
                    default -> throw new Unsupported("rarity mapper " + enumName(f, "rarityValueMapper"));
                };
                yield push(17, in, noise(get(f, "noise")), kind);
            }
            case "Marker" -> {
                int in = node(fn(f, "wrapped"));
                yield switch (enumName(f, "type")) {
                    case "Interpolated" -> push(19, in);
                    case "FlatCache" -> push(20, in);
                    case "Cache2D" -> push(21, in);
                    case "CacheOnce" -> push(22, in);
                    // its cell cache returns values for the wrong cell during
                    // slice filling; vanilla never puts it in a router
                    default -> throw new Unsupported("marker " + enumName(f, "type"));
                };
            }
            case "BlendAlpha" -> push(23);
            case "BlendOffset" -> push(24);
            case "BlendDensity" -> push(25, node(fn(f, "input")));
            case "BeardifierMarker" -> push(26);
            default -> throw new Unsupported("unsupported density function " + type);
        };
    }

    private int spline(CubicSpline<?, ?> s) {
        Integer known = splines.get(s);
        if (known != null) return known;
        if (s instanceof CubicSpline.Constant<?, ?> c) {
            words.add(100);
            words.add(Float.floatToRawIntBits(c.value()) & 0xFFFFFFFFL);
        } else if (s instanceof CubicSpline.Multipoint<?, ?> m) {
            if (!(m.coordinate() instanceof DensityFunctions.Spline.Coordinate coordinate)) {
                throw new Unsupported("spline coordinate " + m.coordinate().getClass().getName());
            }
            int coord = node(coordinate.function().value());
            int[] values = new int[m.values().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = spline(m.values().get(i));
            }
            words.add(101);
            words.add(coord);
            words.add(values.length);
            for (float loc : m.locations()) words.add(Float.floatToRawIntBits(loc) & 0xFFFFFFFFL);
            for (int v : values) words.add(v);
            for (float d : m.derivatives()) words.add(Float.floatToRawIntBits(d) & 0xFFFFFFFFL);
        } else {
            throw new Unsupported("spline type " + s.getClass().getName());
        }
        int id = splineCount++;
        splines.put(s, id);
        return id;
    }

    private long noise(Object holderObject) {
        DensityFunction.NoiseHolder holder = (DensityFunction.NoiseHolder) holderObject;
        NormalNoise noise = holder.noise();
        if (noise == null) return 0; // NoiseHolder.getValue returns 0 then, same on the native side
        Object handle = ((NativeNoiseHolder) noise).oxidyn$nativeHandle();
        if (handle == null) throw new Unsupported("normal noise didn't compile");
        keepAlive.add(handle);
        return backend.noiseAddress(handle);
    }

    private int push(long kind, long... args) {
        words.add(kind);
        words.addElements(words.size(), args);
        return nodeCount++;
    }

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    // --- record access ---

    private static Object get(Object record, String component) {
        Method m = ACCESSORS.computeIfAbsent(record.getClass(), DensityCompiler::accessors).get(component);
        if (m == null) throw new Unsupported(record.getClass().getName() + " has no component " + component);
        try {
            return m.invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new Unsupported("can't read " + component + " of " + record.getClass().getName() + ": " + e);
        }
    }

    private static Map<String, Method> accessors(Class<?> type) {
        Map<String, Method> out = new HashMap<>();
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) return out;
        for (RecordComponent c : components) {
            Method m = c.getAccessor();
            m.setAccessible(true);
            out.put(c.getName(), m);
        }
        return out;
    }

    private static DensityFunction fn(Object r, String c) {
        return (DensityFunction) get(r, c);
    }

    private static double dbl(Object r, String c) {
        return (Double) get(r, c);
    }

    private static long integer(Object r, String c) {
        return (Integer) get(r, c);
    }

    private static String enumName(Object r, String c) {
        return ((Enum<?>) get(r, c)).name();
    }
}
