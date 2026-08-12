package dev.scx.ray_tracing;

public class Vector3Math {

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

    /// dot
    public static double dot(Vector3 a, Vector3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    /// cross
    public static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
    }

    /// length
    public static double length(Vector3 a) {
        return Math.sqrt(lengthSquared(a));
    }

    /// lengthSquared
    public static double lengthSquared(Vector3 a) {
        return a.x * a.x + a.y * a.y + a.z * a.z;
    }

    /// negate
    public static Vector3 negate(Vector3 a) {
        return new Vector3(-a.x, -a.y, -a.z);
    }

    /// normalize
    public static Vector3 normalize(Vector3 a) {
        var length = length(a);
        if (length == 0) {
            return new Vector3(0, 0, 0);
        }
        return div(a, length);
    }

    /// distance
    public static double distance(Vector3 a, Vector3 b) {
        return length(sub(a,b));
    }

    /// reflect
    public static Vector3 reflect(Vector3 direction, Vector3 normal) {
        var num = -2 * dot(normal, direction);
        return new Vector3(num * normal.x + direction.x, num * normal.y + direction.y, num * normal.z + direction.z);
    }

}
