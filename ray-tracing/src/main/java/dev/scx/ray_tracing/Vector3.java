package dev.scx.ray_tracing;

import java.util.Objects;

public class Vector3 {

    public double x;
    public double y;
    public double z;

    public Vector3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /// 创建一个副本
    public Vector3 copy() {
        return new Vector3(x, y, z);
    }

    /// plus
    public Vector3 plus(Vector3 v3) {
        return new Vector3(x + v3.x, y + v3.y, z + v3.z);
    }

    /// minus
    public Vector3 minus(Vector3 v3) {
        return new Vector3(x - v3.x, y - v3.y, z - v3.z);
    }

    /// dot
    public double dot(Vector3 v3) {
        return x * v3.x + y * v3.y + z * v3.z;
    }

    /// cross
    public Vector3 cross(Vector3 v3) {
        return new Vector3(y * v3.z - z * v3.y, z * v3.x - x * v3.z, x * v3.y - y * v3.x);
    }

    /// length
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /// normalized
    public Vector3 normalized() {
        var length = length();
        if (length == 0) {
            return new Vector3(0, 0, 0);
        }
        return new Vector3(x / length, y / length, z / length);
    }

    /// negated
    public Vector3 negated() {
        return new Vector3(-x, -y, -z);
    }

    // ************************** 重写方法 **************************

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof Vector3 vector3)) {
            return false;
        }
        return Double.compare(x, vector3.x) == 0 && Double.compare(y, vector3.y) == 0 && Double.compare(z, vector3.z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "Vector3(" + x + ", " + y + ", " + z + ')';
    }

}
