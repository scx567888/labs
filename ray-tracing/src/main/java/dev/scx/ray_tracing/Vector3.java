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
        return Vector3Math.add(this, v);
    }

    /// sub
    public Vector3 sub(Vector3 v) {
        return Vector3Math.sub(this, v);
    }

    /// mul
    public Vector3 mul(double s) {
        return Vector3Math.mul(this, s);
    }

    /// div
    public Vector3 div(double s) {
        return Vector3Math.div(this, s);
    }

    /// negated
    public Vector3 negated() {
        return Vector3Math.negate(this);
    }

    /// dot
    public double dot(Vector3 v) {
        return Vector3Math.dot(this, v);
    }

    /// cross
    public Vector3 cross(Vector3 v) {
        return Vector3Math.cross(this, v);
    }

    /// length
    public double length() {
        return Vector3Math.length(this);
    }

    /// lengthSquared
    public double lengthSquared() {
        return Vector3Math.lengthSquared(this);
    }

    /// normalized
    public Vector3 normalized() {
        return Vector3Math.normalize(this);
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
