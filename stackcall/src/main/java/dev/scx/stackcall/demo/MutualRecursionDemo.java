package dev.scx.stackcall.demo;

import dev.scx.stackcall.StackCall;

import static dev.scx.stackcall.StackCalls.call;
import static dev.scx.stackcall.StackCalls.done;

public final class MutualRecursionDemo {

    private MutualRecursionDemo() {
    }

    public static StackCall<Boolean, RuntimeException> even(int n) {
        if (n == 0) {
            return done(true);
        }
        return call(() -> odd(n - 1));
    }

    public static StackCall<Boolean, RuntimeException> odd(int n) {
        if (n == 0) {
            return done(false);
        }
        return call(() -> even(n - 1));
    }
}
