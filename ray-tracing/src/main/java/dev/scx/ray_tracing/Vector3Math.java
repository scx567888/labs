package dev.scx.ray_tracing;

public class Vector3Math {

    /// add
    public static Vector3 add(Vector3 a,Vector3 b) {
        return new Vector3(a.x + b.x, a.y + b.y, a.z + b.z);
    }

    /// sub
    public static Vector3 sub(Vector3 a,Vector3 b) {
        return new Vector3(a.x - b.x, a.y - b.y, a.z - b.z);
    }

    /// mul
    public static Vector3 mul(Vector3 a,double s) {
        return new Vector3(a.x * s, a.y * s, a.z * s);
    }

    /// div
    public static Vector3 div(Vector3 a,double s) {
        return new Vector3(a.x / s, a.y / s, a.z / s);
    }

    /// dot
    public static double dot(Vector3 a,Vector3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    /// cross
    public static Vector3 cross(Vector3 a,Vector3 b) {
        return new Vector3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }

    /// length
    public static double length(Vector3 a) {
        return Math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z);
    }

    /// lengthSquared
    public static double lengthSquared(Vector3 a) {
        return a.x * a.x + a.y * a.y + a.z * a.z;
    }

    /// negated
    public static Vector3 negate(Vector3 a) {
        return new Vector3(-a.x, -a.y, -a.z);
    }

    /// normalized
    public static Vector3 normalize(Vector3 a) {
        var length = length(a);
        if (length == 0) {
            return new Vector3(0, 0, 0);
        }
        return new Vector3(a.x / length, a.y / length, a.z / length);
    }

    /// distance
    public static double distance(Vector3 a, Vector3 b) {
        var num1 = a.x - b.x;
        var num2 = a.y - b.y;
        var num3 = a.z - b.z;
        return Math.sqrt(num1 * num1 + num2 * num2 + num3 * num3);
    }

    /// reflect
    public static Vector3 reflect(Vector3 direction, Vector3 normal) {
        var num = -2f * normal.dot(direction);
        return new Vector3(num * normal.x + direction.x, num * normal.y + direction.y, num * normal.z + direction.z);
    }

}
