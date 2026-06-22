package dev.scx.data.mysql_x.parser;

import dev.scx.data.query.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

import static dev.scx.array.ScxArray.toWrapper;
import static java.util.Collections.addAll;

/**
 * @author scx567888
 * @version 0.0.1
 */
public class MySQLXDaoWhereParser {

    public static final MySQLXDaoWhereParser WHERE_PARSER = new MySQLXDaoWhereParser();

    public static Object[] toObjectArray(Object source) {
        if (source instanceof Object[] objectArr) {
            return objectArr;
        }
        if (source == null) {
            return new Object[0];
        }
        if (source instanceof Collection<?> collection) {
            return collection.toArray();
        }
        if (source.getClass().isArray()) {
            return switch (source) {
                case byte[] arr -> toWrapper(arr);
                case short[] arr -> toWrapper(arr);
                case int[] arr -> toWrapper(arr);
                case long[] arr -> toWrapper(arr);
                case float[] arr -> toWrapper(arr);
                case double[] arr -> toWrapper(arr);
                case boolean[] arr -> toWrapper(arr);
                case char[] arr -> toWrapper(arr);
                default -> throw new IllegalStateException("错误值 : " + source);
            };
        }
        throw new IllegalArgumentException("源数据无法转换为数组对象 !!!");
    }

    /// 创建重复字符串 (带分隔符) 拓展了 {@link String#repeat(int)} 无法添加分隔符的功能
    ///
    /// @param str       源字符串
    /// @param delimiter 分隔符
    /// @param count     重复次数
    /// @return 结果
    public static String repeat(String str, String delimiter, int count) {
        if (count == 0) {
            return "";
        }
        var element = str + delimiter;
        var result = element.repeat(count);
        return result.substring(0, result.length() - delimiter.length());
    }

    public WhereClause parseEqual(Condition w) {
        var whereParams = new Object[]{w.value1()};
        var whereClause = w.selector() + " " + getWhereKeyWord(w.conditionType()) + " ?";
        return new WhereClause(whereClause, whereParams);
    }

    public WhereClause parseLike(Condition w) {
        var whereParams = new Object[]{w.value1()};
        var whereClause = w.selector() + " " + getWhereKeyWord(w.conditionType()) + " '%?%'";
        return new WhereClause(whereClause, whereParams);
    }

    public WhereClause parseIn(Condition w) {
        //移除空值并去重
        var whereParams = Arrays.stream(toObjectArray(w.value1())).filter(Objects::nonNull).distinct().toArray();
        //长度为空是抛异常
        if (whereParams.length == 0) {
            throw new IllegalArgumentException("");
        }
        var v1 = "(" + repeat("?", ", ", whereParams.length) + ")";
        var whereClause = w.selector() + " " + getWhereKeyWord(w.conditionType()) + " " + v1;
        return new WhereClause(whereClause, whereParams);
    }

    public WhereClause parseBetween(Condition w) {
        var whereParams = new Object[]{w.value1(), w.value2()};
        var whereClause = w.selector() + " " + getWhereKeyWord(w.conditionType()) + " ? AND ?";
        return new WhereClause(whereClause, whereParams);
    }

    public WhereClause parse(Where obj) {
        return switch (obj) {
            case WhereClause w -> parseWhereClause(w);
            case Junction l -> parseLogic(l);
            case Condition w -> parseCondition(w);
            default -> throw new IllegalArgumentException("Unknown WhereClause: " + obj);
        };
    }

    protected WhereClause parseWhereClause(WhereClause w) {
        return w;
    }

    protected final WhereClause parseLogic(Junction l) {
        var clauses = new ArrayList<String>();
        var whereParams = new ArrayList<>();
        for (var c : l.clauses()) {
            var w = parse(c);
            if (w != null && !w.isEmpty()) {
                clauses.add(w.expression());
                addAll(whereParams, w.params());
            }
        }
        var clause = String.join(" " + getLogicKeyWord(l) + " ", clauses);
        //只有 子句数量 大于 1 时, 我们才在两端拼接 括号
        if (clauses.size() > 1) {
            clause = "(" + clause + ")";
        }
        return new WhereClause(clause, whereParams.toArray());
    }

    protected String getLogicKeyWord(Junction logicType) {
        return switch (logicType) {
            case Or o -> "OR";
            case And a -> "AND";
        };
    }

    protected WhereClause parseCondition(Condition body) {
        return switch (body.conditionType()) {
            case EQ, NE,
                 LT, LTE,
                 GT, GTE,
                 LIKE_REGEX, NOT_LIKE_REGEX -> parseEqual(body);
            case LIKE, NOT_LIKE -> parseLike(body);
            case IN, NOT_IN -> parseIn(body);
            case BETWEEN, NOT_BETWEEN -> parseBetween(body);
        };
    }

    public String getWhereKeyWord(ConditionType whereType) {
        return switch (whereType) {
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case LIKE, LIKE_REGEX -> "LIKE";
            case NOT_LIKE, NOT_LIKE_REGEX -> "NOT LIKE";
            case IN -> "IN";
            case NOT_IN -> "NOT IN";
            case BETWEEN -> "BETWEEN";
            case NOT_BETWEEN -> "NOT BETWEEN";
        };
    }

}
