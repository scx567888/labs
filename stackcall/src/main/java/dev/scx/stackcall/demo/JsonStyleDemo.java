package dev.scx.stackcall.demo;

import dev.scx.stackcall.StackCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.scx.stackcall.StackCalls.*;

/**
 * 不依赖 Jackson 的 JSON 风格示例，用来展示解析器和序列化器的控制流形状。
 */
public final class JsonStyleDemo {

    private JsonStyleDemo() {
    }

    /**
     * 解析器式模式：解析 child，挂入 parent，然后恢复 parent。
     */
    public static StackCall<ObjectNode, RuntimeException> attachChildThenResume(
        ObjectNode parent,
        String name,
        StackCall<Node, RuntimeException> childComputation
    ) {
        return call(
            () -> childComputation,
            child -> parent.values().put(name, child),
            () -> done(parent)
        );
    }

    /**
     * 序列化器式模式：开始标记 -> 所有孩子 -> 结束标记。
     */
    public static StackCall<Void, RuntimeException> writeNode(
        Node node,
        StringBuilder output
    ) {
        return switch (node) {
            case ValueNode value -> effect(() -> {
                output.append('"').append(value.value()).append('"');
            });

            case ArrayNode array -> sequence(
                () -> effect(() -> output.append('[')),
                () -> writeArrayValues(array.values(), output),
                () -> effect(() -> output.append(']'))
            );

            case ObjectNode object -> sequence(
                () -> effect(() -> output.append('{')),
                () -> writeObjectEntries(object.values(), output),
                () -> effect(() -> output.append('}'))
            );
        };
    }

    private static StackCall<Void, RuntimeException> writeArrayValues(
        List<Node> values,
        StringBuilder output
    ) {
        var first = new boolean[]{true};

        return forEach(values.iterator(), value -> sequence(
            () -> effect(() -> {
                if (!first[0]) {
                    output.append(',');
                }
                first[0] = false;
            }),
            () -> call(() -> writeNode(value, output))
        ));
    }

    private static StackCall<Void, RuntimeException> writeObjectEntries(
        Map<String, Node> values,
        StringBuilder output
    ) {
        var first = new boolean[]{true};

        return forEach(values.entrySet().iterator(), entry -> sequence(
            () -> effect(() -> {
                if (!first[0]) {
                    output.append(',');
                }
                first[0] = false;
                output.append('"').append(entry.getKey()).append("\":");
            }),
            () -> call(() -> writeNode(entry.getValue(), output))
        ));
    }

    public sealed interface Node permits ValueNode, ArrayNode, ObjectNode {
    }

    public record ValueNode(String value) implements Node {
    }

    public record ArrayNode(List<Node> values) implements Node {
        public ArrayNode() {
            this(new ArrayList<>());
        }
    }

    public record ObjectNode(Map<String, Node> values) implements Node {
        public ObjectNode() {
            this(new LinkedHashMap<>());
        }
    }
}
