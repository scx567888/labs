package dev.scx.data.mysql_x;

import com.mysql.cj.xdevapi.*;
import dev.scx.data.field_policy.FieldPolicy;
import dev.scx.node.*;

/**
 * @author scx567888
 * @version 0.0.1
 */
class JsonHelper {

    public static JsonValue toJsonValue(Node jsonNode) {
        if (jsonNode instanceof ObjectNode objectNode) {
            var dbDoc = new DbDocImpl();
            for (var next : objectNode) {
                dbDoc.add(next.getKey(), toJsonValue(next.getValue()));
            }
            return dbDoc;
        } else if (jsonNode instanceof ArrayNode arrayNode) {
            var jsonValue = new JsonArray();
            for (var node : arrayNode) {
                jsonValue.add(toJsonValue(node));
            }
            return jsonValue;
        } else if (jsonNode instanceof NumberNode numericNode) {
            return new JsonNumber().setValue(numericNode.asString());
        } else if (jsonNode instanceof StringNode textNode) {
            return new JsonString().setValue(textNode.asString());
        } else if (jsonNode instanceof NullNode nullNode) {
            return JsonLiteral.NULL;
        } else if (jsonNode instanceof BooleanNode booleanNode) {
            boolean aBoolean = booleanNode.asBoolean();
            return aBoolean ? JsonLiteral.TRUE : JsonLiteral.FALSE;
        }
        throw new IllegalArgumentException("未知的 jsonNode 类型 !!!");
    }

    public static ObjectNode filterObjectNode(ObjectNode objectNode, FieldPolicy filter) {
        return switch (filter.getFilterMode()) {
            case EXCLUDED -> {
                for (String s : filter.getFieldNames()) {
                    objectNode.remove(s);
                }
                yield objectNode;
            }
            case INCLUDED -> {
                var newObjectNode = new ObjectNode();
                for (var s : filter.getFieldNames()) {
                    newObjectNode.put(s, objectNode.get(s));

                }
                yield newObjectNode;
            }
        };
    }

    public static DbDoc filterDbDoc(DbDoc dbDoc, FieldPolicy filter) {
        return switch (filter.getFilterMode()) {
            case EXCLUDED -> {
                for (String s : filter.getFieldNames()) {
                    dbDoc.remove(s);
                }
                yield dbDoc;
            }
            case INCLUDED -> {
                var newDbDoc = new DbDocImpl();
                for (var s : filter.getFieldNames()) {
                    newDbDoc.put(s, dbDoc.get(s));
                }
                yield newDbDoc;
            }
        };
    }

    public static Node toObjectNode(JsonValue jsonValue) {
        if (jsonValue instanceof DbDoc dbDoc) {
            var objectNode = new ObjectNode();
            dbDoc.forEach((key, value) -> {
                objectNode.put(key, toObjectNode(value));
            });
            return objectNode;
        } else if (jsonValue instanceof JsonArray jsonArray) {
            var arrayNode = new ArrayNode();
            for (var node : jsonArray) {
                arrayNode.add(toObjectNode(node));
            }
            return arrayNode;
        } else if (jsonValue instanceof JsonNumber jsonNumber) {
            return new BigDecimalNode(jsonNumber.getBigDecimal());
        } else if (jsonValue instanceof JsonString jsonString) {
            return new StringNode(jsonString.getString());
        } else if (jsonValue instanceof JsonLiteral jsonLiteral) {
            return switch (jsonLiteral) {
                case NULL -> NullNode.NULL;
                case TRUE -> BooleanNode.TRUE;
                case FALSE -> BooleanNode.FALSE;
            };
        }
        throw new IllegalArgumentException("未知的 jsonValue 类型 !!!");
    }

}
