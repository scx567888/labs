package dev.scx.stackcall;

import dev.scx.function.Function0;
import dev.scx.function.Function1;

/**
 * 一段可由显式栈执行的同步计算。
 *
 * <p>业务代码通常只需要使用 {@link StackCalls} 中的静态方法，
 * 不需要直接构造本接口的三个实现类型。</p>
 *
 * @param <T> 计算结果类型
 * @param <X> 计算可能抛出的异常类型
 */
public sealed interface StackCall<T, X extends Throwable>
    permits StackCall.Done, StackCall.Call, StackCall.Then {

    /**
     * 当前计算完成后，对结果做普通、立即的转换。
     */
    default <R> StackCall<R, X> map(
        Function1<? super T, ? extends R, X> mapper
    ) {
        return then(value -> StackCalls.done(mapper.apply(value)));
    }

    /**
     * 当前计算完成后，继续另一段 StackCall 计算。
     */
    default <R> StackCall<R, X> then(
        Function1<? super T, ? extends StackCall<R, X>, X> continuation
    ) {
        return new Then<>(this, continuation);
    }

    /**
     * 使用默认同步执行器运行到结束。
     */
    default T run() throws X {
        return StackCalls.run(this);
    }

    /**
     * 当前计算已经得到结果。
     */
    record Done<T, X extends Throwable>(T value)
        implements StackCall<T, X> {
    }

    /**
     * 延迟产生下一段计算，防止此处立即增加 JVM 调用栈。
     */
    record Call<T, X extends Throwable>(
        Function0<? extends StackCall<T, X>, X> invocation
    ) implements StackCall<T, X> {
    }

    /**
     * 先执行 source，再把结果交给 continuation。
     */
    record Then<A, T, X extends Throwable>(
        StackCall<A, X> source,
        Function1<? super A, ? extends StackCall<T, X>, X> continuation
    ) implements StackCall<T, X> {
    }
}
