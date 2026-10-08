package mirror;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static mirror.RefStaticMethod.getProtoType;

@SuppressWarnings("unchecked")
public class RefMethod<T> {
    private Method method;

    public RefMethod(Class<?> cls, Field field) throws NoSuchMethodException {
        if (field.isAnnotationPresent(MethodParams.class)) {
            Class<?>[] types = field.getAnnotation(MethodParams.class).value();
            for (int i = 0; i < types.length; i++) {
                Class<?> clazz = types[i];
                if (clazz.getClassLoader() == getClass().getClassLoader()) {
                    try {
                        Class.forName(clazz.getName());
                        Class<?> realClass = (Class<?>) clazz.getField("TYPE").get(null);
                        types[i] = realClass;
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            this.method = cls.getDeclaredMethod(field.getName(), types);
            this.method.setAccessible(true);
        } else if (field.isAnnotationPresent(MethodReflectParams.class)) {
            String[] typeNames = field.getAnnotation(MethodReflectParams.class).value();
            Class<?>[] types = new Class<?>[typeNames.length];
            for (int i = 0; i < typeNames.length; i++) {
                Class<?> type = getProtoType(typeNames[i]);
                if (type == null) {
                    try {
                        type = Class.forName(typeNames[i]);
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                }
                types[i] = type;
            }
            this.method = cls.getDeclaredMethod(field.getName(), types);
            this.method.setAccessible(true);
        }
        else {
            for (Method method : cls.getDeclaredMethods()) {
                if (method.getName().equals(field.getName())) {
                    this.method = method;
                    this.method.setAccessible(true);
                    break;
                }
            }
        }
        if (this.method == null) {
            throw new NoSuchMethodException(field.getName());
        }
    }

    public T call(Object receiver, Object... args) {
        try {
            return (T) this.method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            cause.printStackTrace();
            // TwinBox 2.1.61：被吞的异常同步落 TwinBox 日志文件——printStackTrace
            // 只进 logcat 标准流，用户导出常抓不到，真死因就这么丢过一次
            // （抖音 40.6 makeApplication 事件）。行为不变（仍返回 null），
            // 只是把现场留下来。
            try {
                dev.twinbox.app.TLog.e("V|Mirror", "swallowed exception from "
                        + this.method.getDeclaringClass().getSimpleName() + "."
                        + this.method.getName(), cause);
            } catch (Throwable ignore) {
                // TLog 不可用时退回纯 printStackTrace（别让日志工具反杀框架）
            }
        } catch (Throwable e) {
            e.printStackTrace();
            try {
                dev.twinbox.app.TLog.e("V|Mirror", "swallowed throwable from "
                        + this.method.getDeclaringClass().getSimpleName() + "."
                        + this.method.getName(), e);
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    public T callWithException(Object receiver, Object... args) throws Throwable {
        try {
            return (T) this.method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() != null) {
                throw e.getCause();
            }
            throw e;
        }
    }

    public Class<?>[] paramList() {
        return method.getParameterTypes();
    }
}