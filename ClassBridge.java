package package-name;

import android.annotation.SuppressLint;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by puruXpuru on 6/20/18.
 */

public class ClassBridge {

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tag {
        String tag() default "";

        int threadMode() default SelfThread;

        boolean setter() default false; // get or set
    }

    private static class TagNode implements Serializable {
        String tag;
        boolean isMethod;
        int threadMode;
        Object instance;
        Object object;
        boolean isSetter;
    }

    public final static int UIThread = 0;
    public final static int NewThread = 1;
    public final static int SelfThread = 2;

    static ConcurrentHashMap<String, TagNode> tags = new ConcurrentHashMap<>();
    static ExecutorService executor = Executors.newFixedThreadPool(5);
    static ClassBridge classBridge = new ClassBridge();

    // mark down the classes.
    public static void mark(Object instance) {

        final Class c = instance.getClass();
        Field[] fields = c.getDeclaredFields();
        for (Field f : fields) {
            Annotation[] annos = f.getAnnotations();
            for (Annotation anno : annos) {
                if (anno instanceof Tag) {
                    f.setAccessible(true);
                    Tag t = (Tag) anno;
                    TagNode tn = new TagNode();
                    tn.threadMode = t.threadMode();
                    tn.isMethod = false;
                    tn.object = f;
                    tn.isSetter = t.setter();
                    tn.tag = t.tag();
                    tn.instance = instance;
                    if (tn.tag.length() == 0) {
                        tn.tag = f.getName();
                    }
                    tags.put(tn.tag, tn);
                }
            }

        }

        Method[] methods = c.getDeclaredMethods();
        for (Method m : methods) {
            Annotation[] annos = m.getAnnotations();
            for (Annotation anno : annos) {
                if (anno instanceof Tag) {
                    m.setAccessible(true);
                    Tag r = (Tag) anno;
                    TagNode tn = new TagNode();
                    tn.threadMode = r.threadMode();
                    tn.isMethod = true;
                    tn.object = m;
                    tn.tag = r.tag();
                    tn.instance = instance;
                    if (tn.tag.length() == 0) {
                        tn.tag = m.getName();
                    }
                    tags.put(tn.tag, tn);
                }
            }
        }

    }

    public static void remove(String tag) {
        tags.remove(tag);
    }

    public static void removeAll() {
        tags.clear();
    }


    // call a method with the given args and return its result,
    // or either get the field value or set field value with the first argument.
    public static Future<Object> run(String tag, Object... args) {
        TagNode tn = tags.get(tag);
        if (tn == null)
            return null;

        Bridge bridge = classBridge.new Bridge(tn).setArgs(args);
        return bridge.exec();
    }


    @SuppressLint("HandlerLeak")
    private class Bridge extends Handler implements Runnable {

        private TagNode tn;
        private Object[] args;
        private BridgeFuture future;

        public Bridge(TagNode tn) {
            this.tn = tn;
            future = new BridgeFuture();
        }

        public Bridge setArgs(Object... args) {
            this.args = args;
            return this;
        }

        private void invokeOrSetOrGet() {

            if (tn.isMethod) {
                Method m = (Method) tn.object;
                try {
                    Object r = m.invoke(tn.instance, args);
                    future.set(r);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            } else if (tn.isSetter) {
                if (args.length < 1)
                    return;
                Field f = (Field) tn.object;
                try {
                    f.set(tn.instance, args[0]);
                    // still set it though run a setter will not has a result.
                    // for preventing from blocking forever by future.get();
                    future.set(null);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            } else {
                Field f = (Field) tn.object;
                try {
                    Object r = f.get(tn.instance);
                    future.set(r);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }

            }
        }

        @Override
        public void handleMessage(Message msg) {

            if (msg.what == UIThread) {
                invokeOrSetOrGet();
            }
        }

        public Future<Object> exec() {
            switch (tn.threadMode) {

                case UIThread: {
                    if (Looper.getMainLooper() == Looper.myLooper()) {
                        // we are just on the ui thread now.
                        invokeOrSetOrGet();
                        break;
                    } else {
                        Message msg = new Message();
                        msg.what = UIThread;
                        sendMessage(msg);
                    }


                    break;
                }

                case NewThread: {
                    executor.execute(this);
                    break;
                }

                case SelfThread: {
                    invokeOrSetOrGet();
                }
            }
            return future;
        }

        @Override
        public void run() {
            invokeOrSetOrGet();
        }
    }

    private class BridgeFuture implements Future<Object> {

        private AtomicBoolean blocking = new AtomicBoolean(false);
        private AtomicBoolean cancelled = new AtomicBoolean(false);
        private AtomicBoolean done = new AtomicBoolean(false);
        private ConditionVariable cv = new ConditionVariable();
        private boolean cancelledWithInterruption = false;
        private Object result;


        public boolean set(Object result) {
            if (isCancelled() && cancelledWithInterruption) {
                cv.open();
                //tryNotify();
                return false;
            }

            if (!done.compareAndSet(false, true))
                return false;
            this.result = result;
            cv.open();
            return true;
        }

        @Override
        public boolean cancel(boolean b) {
            if (!cancelled.compareAndSet(false, true))
                return false;
            cancelledWithInterruption = b;
            cv.open();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return done.get();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            if (isCancelled() && cancelledWithInterruption)
                throw new InterruptedException();

            if (!isDone())
                cv.block();

            if (isCancelled() && cancelledWithInterruption)
                throw new InterruptedException();

            return result;
        }

        @Override
        public Object get(long l, @NonNull TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
            if (isCancelled() && cancelledWithInterruption)
                throw new InterruptedException();

            if (!isDone()) {
                if (!cv.block(timeUnit.toMillis(l)))
                    throw new TimeoutException();
            }

            if (isCancelled() && cancelledWithInterruption)
                throw new InterruptedException();

            return result;
        }

    }
}
