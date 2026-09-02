package dev.scx.stackcall;

import dev.scx.function.*;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;

// todo 未完成, 本质上是在研究 把递归计算的“调用栈”从 JVM 的隐式栈搬到堆上的显式栈，并用一个执行器恢复这些被暂停的计算，从而实现栈安全的通用递归。

/**
 * StackCall 的构造方法、便利组合器与同步执行器。
 *
 * <p>核心执行模型只有 done、call、then 和 run；其余重载只是语法糖。</p>
 */
public final class StackCalls {

    private StackCalls() {
    }

    /**
     * 相当于普通方法中的 return value。
     */
    public static <T, X extends Throwable> StackCall<T, X> done(T value) {
        return new StackCall.Done<>(value);
    }

    /**
     * 延迟一次可能递归的调用。
     *
     * <p>凡是处在递归环上的调用，都必须通过此方法延迟。</p>
     */
    public static <T, X extends Throwable> StackCall<T, X> call(
        Function0<? extends StackCall<T, X>, X> invocation
    ) {
        return new StackCall.Call<>(Objects.requireNonNull(invocation));
    }

    /**
     * 调用一个子计算，并在其返回后做一次普通计算。
     *
     * <pre>{@code
     * return call(
     *     () -> sum(n - 1),
     *     value -> value + n
     * );
     * }</pre>
     */
    public static <A, R, X extends Throwable> StackCall<R, X> call(
        Function0<? extends StackCall<A, X>, X> invocation,
        Function1<? super A, ? extends R, X> afterReturn
    ) {
        return StackCalls.<A, X>call(invocation).map(afterReturn);
    }

    /**
     * 调用一个子计算；使用其结果执行副作用；然后继续下一个计算。
     *
     * <p>很适合“解析子节点 -> 挂到父节点 -> 恢复父节点”这种结构。</p>
     */
    public static <A, R, X extends Throwable> StackCall<R, X> call(
        Function0<? extends StackCall<A, X>, X> invocation,
        Function1Void<? super A, X> afterReturn,
        Function0<? extends StackCall<R, X>, X> next
    ) {
        Objects.requireNonNull(afterReturn);
        Objects.requireNonNull(next);

        return StackCalls.<A, X>call(invocation).then(value -> {
            afterReturn.apply(value);
            return StackCalls.call(next);
        });
    }

    /**
     * 顺序运行两个计算，第二个计算可以依赖第一个结果，最后组合两个结果。
     */
    public static <A, B, R, X extends Throwable> StackCall<R, X> call(
        Function0<? extends StackCall<A, X>, X> first,
        Function1<? super A, ? extends StackCall<B, X>, X> second,
        Function2<? super A, ? super B, ? extends R, X> result
    ) {
        Objects.requireNonNull(second);
        Objects.requireNonNull(result);

        return StackCalls.<A, X>call(first).then(a ->
            StackCalls.<B, X>call(() -> second.apply(a))
                .map(b -> result.apply(a, b))
        );
    }

    /**
     * 顺序运行三个计算；每一步可以访问所有已经产生的结果；最后组合结果。
     *
     * <p>4～9 元版本可以按此模式机械生成。</p>
     */
    public static <A, B, C, R, X extends Throwable> StackCall<R, X> call(
        Function0<? extends StackCall<A, X>, X> first,
        Function1<? super A, ? extends StackCall<B, X>, X> second,
        Function2<? super A, ? super B, ? extends StackCall<C, X>, X> third,
        Function3<? super A, ? super B, ? super C, ? extends R, X> result
    ) {
        Objects.requireNonNull(second);
        Objects.requireNonNull(third);
        Objects.requireNonNull(result);

        return StackCalls.<A, X>call(first).then(a ->
            StackCalls.<B, X>call(() -> second.apply(a)).then(b ->
                StackCalls.<C, X>call(() -> third.apply(a, b))
                    .map(c -> result.apply(a, b, c))
            )
        );
    }

    /**
     * 把一个可能抛异常的无返回值操作包装成 StackCall。
     */
    public static <X extends Throwable> StackCall<Void, X> effect(
        Function0Void<X> action
    ) {
        Objects.requireNonNull(action);

        return call(() -> {
            action.apply();
            return done(null);
        });
    }

    /**
     * 依次执行若干无返回值计算。
     */
    @SafeVarargs
    public static <X extends Throwable> StackCall<Void, X> sequence(
        Function0<? extends StackCall<Void, X>, X>... steps
    ) {
        Objects.requireNonNull(steps);
        return sequence(steps, 0);
    }

    private static <X extends Throwable> StackCall<Void, X> sequence(
        Function0<? extends StackCall<Void, X>, X>[] steps,
        int index
    ) {
        if (index >= steps.length) {
            return done(null);
        }

        return StackCalls.<Void, X>call(steps[index]).then(ignored ->
            StackCalls.call(() -> sequence(steps, index + 1))
        );
    }

    /**
     * 栈安全地顺序处理 Iterator 中的所有元素。
     */
    public static <A, X extends Throwable> StackCall<Void, X> forEach(
        Iterator<? extends A> iterator,
        Function1<? super A, ? extends StackCall<Void, X>, X> action
    ) {
        Objects.requireNonNull(iterator);
        Objects.requireNonNull(action);

        if (!iterator.hasNext()) {
            return done(null);
        }

        var value = iterator.next();

        return StackCalls.<Void, X>call(() -> action.apply(value)).then(ignored ->
            StackCalls.call(() -> forEach(iterator, action))
        );
    }

    /**
     * 默认同步执行器：JVM 调用栈深度保持常量，逻辑 continuation 保存于 ArrayDeque。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, X extends Throwable> T run(
        StackCall<T, X> initial
    ) throws X {
        Objects.requireNonNull(initial);

        StackCall<?, X> current = initial;
        var continuations = new ArrayDeque<Function1<Object, StackCall<?, X>, X>>();

        while (true) {
            switch (current) {
                case StackCall.Done<?, ?> done -> {
                    var value = done.value();

                    if (continuations.isEmpty()) {
                        return (T) value;
                    }

                    current = continuations.pop().apply(value);
                }

                case StackCall.Call<?, ?> call -> {
                    var invocation =
                        (Function0<? extends StackCall<?, X>, X>) (Function0) call.invocation();
                    current = invocation.apply();
                }

                case StackCall.Then<?, ?, ?> then -> {
                    continuations.push(
                        (Function1<Object, StackCall<?, X>, X>) (Function1) then.continuation()
                    );
                    current = (StackCall<?, X>) then.source();
                }
            }
        }
    }
}
