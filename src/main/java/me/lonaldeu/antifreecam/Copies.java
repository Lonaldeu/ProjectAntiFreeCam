package me.lonaldeu.antifreecam;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies server objects (packets) with some fields changed. Packets may be shared between players, so the original
 * is never modified. Since 26.3 several packets are records, whose fields can't be written even by reflection, so
 * records are rebuilt through their canonical constructor; other classes are copied field by field.
 */
final class Copies {

    /** How to copy one class: a record through its canonical constructor, anything else field by field. */
    private record Shape(Constructor<?> canonical, Method[] accessors, String[] names, Field[] fields) {
    }

    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();
    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Copies() {
    }

    /** A copy of {@code source} with the named fields set to new values, given as name, value, name, value... */
    static Object with(Object source, Object... changes) {
        Shape shape = SHAPES.computeIfAbsent(source.getClass(), Copies::shapeOf);
        try {
            if (shape.canonical() != null) {
                Object[] args = new Object[shape.names().length];
                for (int i = 0; i < args.length; i++) args[i] = shape.accessors()[i].invoke(source);
                for (int c = 0; c < changes.length; c += 2) args[indexOf(shape.names(), (String) changes[c])] = changes[c + 1];
                return shape.canonical().newInstance(args);
            }
            Object copy = UNSAFE.allocateInstance(source.getClass());
            for (Field f : shape.fields()) f.set(copy, f.get(source));
            for (int c = 0; c < changes.length; c += 2)
                shape.fields()[indexOf(shape.names(), (String) changes[c])].set(copy, changes[c + 1]);
            return copy;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Shape shapeOf(Class<?> type) {
        try {
            if (type.isRecord()) {
                RecordComponent[] components = type.getRecordComponents();
                Class<?>[] types = new Class<?>[components.length];
                Method[] accessors = new Method[components.length];
                String[] names = new String[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = components[i].getType();
                    accessors[i] = components[i].getAccessor();
                    accessors[i].setAccessible(true);
                    names[i] = components[i].getName();
                }
                Constructor<?> canonical = type.getDeclaredConstructor(types);
                canonical.setAccessible(true);
                return new Shape(canonical, accessors, names, null);
            }
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != Object.class; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields())
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
            String[] names = fields.stream().map(Field::getName).toArray(String[]::new);
            return new Shape(null, null, names, fields.toArray(Field[]::new));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(String[] names, String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        throw new IllegalArgumentException("no field " + name);
    }
}
