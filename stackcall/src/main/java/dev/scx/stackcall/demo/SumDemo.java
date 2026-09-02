package dev.scx.stackcall.demo;

import dev.scx.function.Function1;
import dev.scx.stackcall.StackCall;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static dev.scx.stackcall.StackCalls.call;
import static dev.scx.stackcall.StackCalls.done;

public final class SumDemo {

    private SumDemo() {
    }

    /**
     * 非尾递归：子调用返回后还需要执行 + n。
     */
    public static StackCall<Long, RuntimeException> sum(long n) {
        if (n == 0) {
            return done(0L);
        }

        return call(
            () -> sum(n - 1),
            value -> value + n
        );
    }

    static void main() throws IOException {
        walk(new File("xxxx"),(c)->{
            System.out.println(c);
            return false;
        });
        long size = size(new File("xxx"));
        System.out.println(size/1024/1024+"MB");
    }

    static void walk(File file, Function1<File, Boolean, RuntimeException> visitor) throws IOException {
        var stop = visitor.apply(file);
        if (stop) {
            return;
        }
        var files = file.listFiles();
        if (files == null) {
            return;
        }
        for (var f : files) {
            walk(f, visitor);
        }
    }

    static long size(File file) {
        if (file.isFile()) {
            return file.length();
        }

        long total = 0;

        var files = file.listFiles();
        if (files == null) {
            return total;
        }

        for (var f : files) {
            total += size(f);
        }

        return total;
    }

}
