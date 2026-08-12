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
    public Vector3 plus(Vector3 vector3) {
        return new Vector3(
            x + vector3.x,
            y + vector3.y,
            z + vector3.z
        );
    }

    /// minus
    public Vector3 minus(Vector3 vector3) {
        return new Vector3(
            x - vector3.x,
            y - vector3.y,
            z - vector3.z
        );
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
