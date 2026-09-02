package dev.scx.stackcall.demo;

import static dev.scx.stackcall.demo.FibonacciDemo.fibonacci;
import static dev.scx.stackcall.demo.MutualRecursionDemo.even;
import static dev.scx.stackcall.demo.SumDemo.sum;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        var sumResult = sum(1_000_000).run();
        assertEquals(500_000_500_000L, sumResult, "sum");

        var evenResult = even(1_000_000).run();
        assertEquals(true, evenResult, "mutual recursion");

        var fibonacciResult = fibonacci(20).run();
        assertEquals(6_765L, fibonacciResult, "fibonacci");

        var root = new JsonStyleDemo.ObjectNode();
        var array = new JsonStyleDemo.ArrayNode();
        array.values().add(new JsonStyleDemo.ValueNode("a"));
        array.values().add(new JsonStyleDemo.ValueNode("b"));
        root.values().put("items", array);

        var output = new StringBuilder();
        JsonStyleDemo.writeNode(root, output).run();
        assertEquals("{\"items\":[\"a\",\"b\"]}", output.toString(), "json style serializer");

        System.out.println("All prototype checks passed.");
        System.out.println("sum(1_000_000) = " + sumResult);
        System.out.println("even(1_000_000) = " + evenResult);
        System.out.println("fibonacci(20) = " + fibonacciResult);
        System.out.println("serialized = " + output);
    }

    private static void assertEquals(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                name + " failed: expected=" + expected + ", actual=" + actual
            );
        }
    }
}
