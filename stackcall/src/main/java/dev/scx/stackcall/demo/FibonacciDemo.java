package dev.scx.stackcall.demo;

import dev.scx.stackcall.StackCall;

import static dev.scx.stackcall.StackCalls.call;
import static dev.scx.stackcall.StackCalls.done;

public final class FibonacciDemo {

    private FibonacciDemo() {
    }

    /**
     * 展示两个递归结果的组合。这里只用于展示 API，算法本身仍是指数复杂度。
     */
    public static StackCall<Long, RuntimeException> fibonacci(int n) {
        if (n <= 1) {
            return done((long) n);
        }

        return call(
            () -> fibonacci(n - 1),
            ignoredFirst -> fibonacci(n - 2),
            Long::sum
        );
    }
}
