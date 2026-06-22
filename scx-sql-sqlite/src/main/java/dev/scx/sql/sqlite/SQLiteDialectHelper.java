package dev.scx.sql.sqlite;

import dev.scx.sql.schema.DataTypeKind;

/// SQLiteDialectHelper
///
/// @author scx567888
/// @version 0.0.1
final class SQLiteDialectHelper {

    public static DataTypeKind dialectTypeNameToDataTypeKind(String dialectTypeName) {
        if ("TEXT".equalsIgnoreCase(dialectTypeName)) {
            return DataTypeKind.VARCHAR;
        } else if ("INTEGER".equalsIgnoreCase(dialectTypeName)) {
            return DataTypeKind.INT;
        } else {
            throw new IllegalArgumentException("未知方言数据类型 : " + dialectTypeName);
        }
    }

    public static String dataTypeKindToDialectTypeName(DataTypeKind dataTypeKind) {
        return switch (dataTypeKind) {
            case TINYINT, SMALLINT, INT, BIGINT, BOOLEAN -> "INTEGER";
            case FLOAT, DOUBLE, DECIMAL -> "REAL";
            case DATE, TIME, DATETIME, VARCHAR, TEXT, LONGTEXT, JSON -> "TEXT";
            case BLOB, LONGBLOB -> "BLOB";
        };
    }

}
