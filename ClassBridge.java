package package-name;

import android.annotation.SuppressLint;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static okhttp3.internal.Internal.instance;

/**
 * Created by puruXpuru on 6/20/18.
 */

public class ClassBridge {

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tag{
        /**
         * When the value of tag is empty, it will use the name of the elements
         * who is tagged as a tag value, be careful if the name
         * isn't the unique one in the marked classes, the prior one will be overrode.
         * */
        String tag() default "";

        /**
         * @see ClassBridge#SelfThread
         */
        int threadMode() default SelfThread;

        /**
         * Ignored when it's tagged on method,
         * flag it as a setter or getter, default as getter
         */
        boolean setter() default false; // get or set
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Subscribe {
        /**
         * @see Tag#tag()
         * */
        String tag() default "";

        /**
         * @see Tag#threadMode()
         * */
        int threadMode() default SelfThread;

        /**
         * @see Tag#setter()
         * */
        boolean setter() default false; // get or set
    }

    private static class TagNode implements Serializable, Comparable<TagNode> {
        String tag;
        boolean isMethod;
        int threadMode;
        Object object;
        boolean isSetter;
        WeakReference<Object> refInstance;

        @Override
        public int compareTo(@NonNull TagNode tagNode) {
            if(Objects.equals(tag, tagNode.tag)
                    && this.object == tagNode.object
                    && isSetter == tagNode.isSetter
                    && this.isMethod == tagNode.isMethod)
                return 1;
            return 0;
        }
    }


    /**
     * Pass the invoking information to a Handler, for run on the ui thread
     */
    public final static int UIThread = 0;

    /**
     * Create a new thread to run
     */
    public final static int NewThread = 1;

    /**
     * Run on the current thread who invoked it.
     */
    public final static int SelfThread = 2;

    private static ConcurrentHashMap<String, TagNode> tags = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, ConcurrentSkipListSet<TagNode>> subscribers =
            new ConcurrentHashMap<>();
    /**
     * Cache the Args was published, when there is a new subscription,
     * it will be automatically informed with the cached publications.
     * */
    private static ConcurrentMap<String, Object[]> publishedKeeper = new ConcurrentHashMap<>();
    private static ExecutorService executor = Executors.newFixedThreadPool(5);
    private static ClassBridge classBridge = new ClassBridge();

    // mark down the classes.
    public static void mark(Object instance) {

        final Class c = instance.getClass();
        Field[] fields = c.getDeclaredFields();
        for (Field f : fields) {
            Annotation[] annos = f.getAnnotations();
            if(annos.length == 0)
                continue;

            markWithAnnotations(annos, false, f, f.getName(), instance);
        }

        Method[] methods = c.getDeclaredMethods();
        MarkMethods:
        for (Method m : methods) {
            Annotation[] annos = m.getAnnotations();
            if(annos.length == 0)
                continue;

            markWithAnnotations(annos, true, m, m.getName(), instance);
        }

    }


    private static void markWithAnnotations(Annotation[] annos, boolean isMethod,
                                            AccessibleObject accessibleObject,
                                            String objectName, Object instance){
        MarkAnno:
        for (Annotation anno : annos) {
            do{
                if(!(anno instanceof Tag) && !(anno instanceof Subscribe))
                    continue MarkAnno;
                TagNode tn = new TagNode();
                tn.object = accessibleObject;
                tn.isMethod = isMethod;
                tn.refInstance = new WeakReference<>(instance);
                accessibleObject.setAccessible(true);

                if (anno instanceof Tag) {
                    Tag t = (Tag) anno;
                    tn.tag = t.tag();
                    if (tn.tag.length() == 0) {
                        tn.tag = objectName;//f.getName();
                    }
                    tn.isSetter = t.setter();
                    tn.threadMode = t.threadMode();
                    tags.put(tn.tag, tn);
                } else  {
                    Subscribe t = (Subscribe) anno;
                    tn.tag = t.tag();
                    if (tn.tag.length() == 0) {
                        tn.tag = objectName;//fgetName();
                    }
                    tn.isSetter = t.setter();
                    tn.threadMode = t.threadMode();
                    addToSubscribers(tn);
                }
                continue MarkAnno;

            }while(false);
        }
    }

    private static void addToSubscribers(TagNode tagNode){
        ConcurrentSkipListSet<TagNode> set = subscribers.get(tagNode.tag);
        if(set == null){
            set = new ConcurrentSkipListSet<>();
            subscribers.put(tagNode.tag, set);
        }
        set.add(tagNode);
        Object[] cached = publishedKeeper.get(tagNode.tag);
        if(cached != null){
            classBridge.new Bridge(tagNode).setArgs(cached).exec();
        }
    }

    private static boolean checkInstanceIsRecycled(TagNode tn){
        return tn.refInstance.get() == null;
    }

    /**
     * call a method with the given args and return its result,
     * if it's a field will either get the field value or
     * set the value of the field with the first argument.
     */
    public static Future<Object> run(String tag, Object...args) {
        TagNode tn = tags.get(tag);
        if (tn == null)
            return null;

        if(checkInstanceIsRecycled(tn)){
            tags.remove(tn.tag);
            return null;
        }

        Bridge bridge = classBridge.new Bridge(tn).setArgs(args);
        return bridge.exec();
    }

    /**
     * It will remove the args has been cached, dut to the cached value should be newest usually.
     * */
    public static void publish(String tag, Object...args){

        publishedKeeper.remove(tag);
        ConcurrentSkipListSet<TagNode> set = subscribers.get(tag);
        if(set == null)
            return;
        for(TagNode tn: set){
            if(checkInstanceIsRecycled(tn)){
                continue;
            }
            classBridge.new Bridge(tn).setArgs(args).exec();
        }
    }

    /**
     * @param cache cache the args of published, when a new subscriber is marked
     *              will automatically push the cached value to it.
     * */
    public static void publishAndCache(String tag, boolean cache, Object...args){
        publish(tag, args);
        if(cache)
            publishedKeeper.put(tag, args);
    }

    public static void removeTag(String tag) {
        tags.remove(tag);
    }

    public static void removeAllTags() {
        tags.clear();
    }

    public static void removeCache(String tag){
        publishedKeeper.remove(tag);
    }

    public static void removeAllCache(String tag){
        publishedKeeper.clear();
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

            Object instance = tn.refInstance.get();
            if(instance == null)
                return;
            if (tn.isMethod) {
                Method m = (Method) tn.object;
                try {
                    Object r = m.invoke(instance, args);
                    future.set(r);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            } else if (tn.isSetter) {
                if (args.length < 1)
                    return;
                Field f = (Field) tn.object;
                try {
                    f.set(instance, args[0]);
                    // still set it though run a setter will not has a result.
                    // for preventing from blocking forever by future.get();
                    future.set(null);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            } else {
                Field f = (Field) tn.object;
                try {
                    Object r = f.get(instance);
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

        public @NonNull Future<Object> exec() {
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

        private AtomicBoolean cancelled = new AtomicBoolean(false);
        private AtomicBoolean done = new AtomicBoolean(false);
        private ConditionVariable cv = new ConditionVariable();
        private boolean cancelledWithInterruption = false;
        private Object result;


        public boolean set(Object result) {
            if (isCancelled() && cancelledWithInterruption) {
                cv.open();
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
