package dev.scx.ray_tracing;

/// Vector3 数学运算类.
/// 所有方法均不会修改传入的 Vector3; 返回 Vector3 时会创建新的实例.
public final class Vector3Math {

    // ****************** 基础算数 ******************

    /// add
    public static Vector3 add(Vector3 a, Vector3 b) {
        return new Vector3(a.x + b.x, a.y + b.y, a.z + b.z);
    }

    /// sub
    public static Vector3 sub(Vector3 a, Vector3 b) {
        return new Vector3(a.x - b.x, a.y - b.y, a.z - b.z);
    }

    /// mul
    public static Vector3 mul(Vector3 a, double s) {
        return new Vector3(a.x * s, a.y * s, a.z * s);
    }

    /// div
    public static Vector3 div(Vector3 a, double s) {
        return new Vector3(a.x / s, a.y / s, a.z / s);
    }

    /// scale
    public static Vector3 scale(Vector3 a, Vector3 b) {
        return new Vector3(a.x * b.x, a.y * b.y, a.z * b.z);
    }

    /// negate
    public static Vector3 negate(Vector3 a) {
        return new Vector3(-a.x, -a.y, -a.z);
    }

    // ****************** 向量代数 ******************

    /// dot
    public static double dot(Vector3 a, Vector3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    /// cross
    public static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }

    // ****************** 向量自身 ******************

    /// length
    public static double length(Vector3 a) {
        return Math.sqrt(lengthSquared(a));
    }

    /// lengthSquared
    public static double lengthSquared(Vector3 a) {
        return a.x * a.x + a.y * a.y + a.z * a.z;
    }

    /// normalize
    public static Vector3 normalize(Vector3 a) {
        var length = length(a);
        if (length == 0) {
            return new Vector3(0, 0, 0);
        }
        return div(a, length);
    }

    // ****************** 几何函数 ******************

    /// distance
    public static double distance(Vector3 a, Vector3 b) {
        return length(sub(a, b));
    }

    /// reflect
    public static Vector3 reflect(Vector3 direction, Vector3 normal) {
        return sub(direction, mul(normal, 2.0 * dot(direction, normal)));
    }

}
