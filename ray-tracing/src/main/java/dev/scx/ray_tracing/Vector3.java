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

    /// copy
    public Vector3 copy() {
        return new Vector3(x, y, z);
    }

    /// add
    public Vector3 add(Vector3 v) {
        return new Vector3(x + v.x, y + v.y, z + v.z);
    }

    /// sub
    public Vector3 sub(Vector3 v) {
        return new Vector3(x - v.x, y - v.y, z - v.z);
    }

    /// mul
    public Vector3 mul(double s) {
        return new Vector3(x * s, y * s, z * s);
    }

    /// div
    public Vector3 div(double s) {
        return new Vector3(x / s, y / s, z / s);
    }

    /// dot
    public double dot(Vector3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    /// cross
    public Vector3 cross(Vector3 v) {
        return new Vector3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }

    /// length
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /// lengthSquared
    public double lengthSquared() {
        return x * x + y * y + z * z;
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
