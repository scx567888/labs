package dev.scx.physics;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Random;

import static dev.scx.physics.Helper.runScene;

/**
 * 二维刚体学习沙盒：圆 + 凸多边形，保持原有 Scene 接口。
 * 坐标相对于客户区，线速度相对于桌面；角速度单位 rad/s。
 * Runner 应在客户区尺寸有效时调用一次 start，然后调用 update/render。
 */
public class TestScene2 implements Scene {
    private static final double GRAVITY = 2200;
    private static final double RESTITUTION = 0.18;
    private static final double FRICTION = 0.50;
    private static final double MAX_SPEED = 4000;
    private static final double MAX_SPIN = 45;
    private static final int VELOCITY_ITERATIONS = 12;
    private static final int POSITION_ITERATIONS = 4;
    private static final int MAX_BODIES = 250;
    private static final double EPSILON = 1e-9;
    private static final Color BACKGROUND = new Color(24, 29, 39);

    private final ArrayList<Body> bodies = new ArrayList<>();
    private final Random random = new Random();
    private Kind selected = Kind.CIRCLE;
    private double width, height, windowX, windowY;
    private boolean wasMouseDown, wasSpaceDown, wasResetDown, wasClearDown;
    private double spawnCooldown;
    private long lastNanos, statsStartNanos;
    private int updateCount;
    private double updateFps, maxIntervalMs, shownIntervalMs;
    private double totalCostMs, maxCostMs, shownMeanCostMs, shownMaxCostMs;
    private boolean statsReady;
    private String status = "";
    private double statusTime;

    private enum Kind {
        CIRCLE("Circle"), TRIANGLE("Triangle"), SQUARE("Square"),
        RECTANGLE("Rectangle"), SEMICIRCLE("Semicircle (16 segments)");
        final String label;
        Kind(String label) { this.label = label; }
    }

    @Override
    public void start(SceneContext context) {
        width = context.viewport().width();
        height = context.viewport().height();
        windowX = context.window().x();
        windowY = context.window().y();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Call start after the viewport has a positive size");
        }
        bodies.clear();
        selected = Kind.CIRCLE;
        wasMouseDown = wasSpaceDown = wasResetDown = wasClearDown = false;
        spawnCooldown = statusTime = 0;
        status = "";
        updateCount = 0;
        updateFps = maxIntervalMs = shownIntervalMs = 0;
        totalCostMs = maxCostMs = shownMeanCostMs = shownMaxCostMs = 0;
        statsReady = false;
        seedBalls();
        lastNanos = statsStartNanos = System.nanoTime();
    }

    @Override
    public void update(SceneContext context) {
        long now = System.nanoTime();
        double elapsed = Math.max(1e-6, (now - lastNanos) * 1e-9);
        lastNanos = now;
        updateCount++;
        maxIntervalMs = Math.max(maxIntervalMs, elapsed * 1000);
        statusTime = Math.max(0, statusTime - elapsed);
        if (context.viewport().width() > 0 && context.viewport().height() > 0) {
            advance(context, Math.min(elapsed, 0.05), elapsed);
        }
        double cost = (System.nanoTime() - now) * 1e-6;
        totalCostMs += cost;
        maxCostMs = Math.max(maxCostMs, cost);
        long interval = now - statsStartNanos;
        if (interval >= 1_000_000_000L) {
            updateFps = updateCount * 1e9 / interval;
            shownIntervalMs = maxIntervalMs;
            shownMeanCostMs = totalCostMs / updateCount;
            shownMaxCostMs = maxCostMs;
            statsReady = true;
            updateCount = 0;
            maxIntervalMs = totalCostMs = maxCostMs = 0;
            statsStartNanos = now;
        }
    }

    private void seedBalls() {
        double cell = Math.min(44, Math.min(width / 8, height / 6));
        if (cell < 4) return;
        int rows = Math.max(1, (int) (height * 0.65 / cell));
        int columns = Math.max(1, (int) (width / cell));
        for (int row = 0; row < rows && bodies.size() < MAX_BODIES; row++) {
            for (int col = 0; col < columns && bodies.size() < MAX_BODIES; col++) {
                double radius = cell * (0.35 + random.nextDouble() * 0.14);
                double x = (col + 0.5) * cell + (row % 2) * cell * 0.15;
                if (x + radius <= width) {
                    bodies.add(makeBody(Kind.CIRCLE, x, height - (row + 0.5) * cell, radius));
                }
            }
        }
    }

    private void advance(SceneContext context, double dt, double realDt) {
        double newWidth = context.viewport().width(), newHeight = context.viewport().height();
        double dx = context.window().x() - windowX, dy = context.window().y() - windowY;
        double dw = newWidth - width, dh = newHeight - height;
        // 用真实时间估算墙的速度，避免卡顿时由截断 dt 人为放大碰撞速度。
        double lv = dx / realDt, tv = dy / realDt;
        double rv = (dx + dw) / realDt, bv = (dy + dh) / realDt;
        double minFeature = 20, maxSpeed = 0;
        for (Body b : bodies) {
            minFeature = Math.min(minFeature, b.feature);
            maxSpeed = Math.max(maxSpeed, Math.hypot(b.vx, b.vy) + Math.abs(b.spin) * b.boundRadius);
        }
        double wallTravel = Math.max(Math.max(Math.abs(dx), Math.abs(dy)),
                Math.max(Math.abs(dx + dw), Math.abs(dy + dh)));
        int steps = Math.min(128, Math.max(1, Math.max((int) Math.ceil(dt * 240),
                (int) Math.ceil((maxSpeed * dt + wallTravel) / Math.max(1, minFeature * 0.4)))));
        double step = dt / steps;
        double oldW = width, oldH = height;
        for (int s = 1; s <= steps; s++) {
            double w = oldW + dw * s / steps, h = oldH + dh * s / steps;
            for (Body b : bodies) {
                b.vy += GRAVITY * step;
                b.spin *= Math.exp(-0.6 * step);
                limitSpeed(b);
                b.x += b.vx * step - dx / steps;
                b.y += b.vy * step - dy / steps;
                b.angle = Math.IEEEremainder(b.angle + b.spin * step, Math.PI * 2);
                b.sync();
            }
            ArrayList<Contact> contacts = findContacts(w, h, lv, rv, tv, bv);
            for (Contact c : contacts) c.prepare();
            for (int iteration = 0; iteration < VELOCITY_ITERATIONS; iteration++) {
                for (Contact c : contacts) c.solveVelocity();
            }
            // 单独修正位置，不将重叠修正直接转换为无限大的速度。
            for (int iteration = 0; iteration < POSITION_ITERATIONS; iteration++) {
                for (Contact c : findContacts(w, h, lv, rv, tv, bv)) c.correctPosition();
            }
            for (Body b : bodies) {
                contain(b, w, h, lv, rv, tv, bv);
                limitSpeed(b);
            }
        }
        width = newWidth;
        height = newHeight;
        windowX = context.window().x();
        windowY = context.window().y();
        handleInput(context.input(), realDt, lv, tv);
    }

    private void handleInput(Input input, double dt, double vx, double vy) {
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            if (input.keyDown(KeyEvent.VK_1 + i) || input.keyDown(KeyEvent.VK_NUMPAD1 + i)) selected = kinds[i];
        }
        boolean reset = input.keyDown(KeyEvent.VK_R), clear = input.keyDown(KeyEvent.VK_C);
        boolean mouse = input.mouseDown(MouseEvent.BUTTON1), space = input.keyDown(KeyEvent.VK_SPACE);
        if (clear && !wasClearDown) bodies.clear();
        if (reset && !wasResetDown) { bodies.clear(); seedBalls(); }
        spawnCooldown -= dt;
        if ((mouse && !wasMouseDown) || (space && !wasSpaceDown)
                || ((mouse || space) && spawnCooldown <= 0)) {
            spawn(mouse ? input.mouseX() : width / 2, mouse ? input.mouseY() : 55, vx, vy);
            spawnCooldown = 0.16;
        }
        wasMouseDown = mouse;
        wasSpaceDown = space;
        wasResetDown = reset;
        wasClearDown = clear;
    }

    private void spawn(double x, double y, double vx, double vy) {
        if (bodies.size() >= MAX_BODIES) { message("Body limit reached"); return; }
        if (x < 0 || x > width || y < 0 || y > height) return;
        Body added = makeBody(selected, x, y, 13 + random.nextDouble() * 11);
        // 只允许与其他刚体重叠，不允许在墙外生成。靠边时缩小形状，不移动点击点。
        double fit = 1;
        if (added.minX < x) fit = Math.min(fit, x / (x - added.minX));
        if (added.maxX > x) fit = Math.min(fit, (width - x) / (added.maxX - x));
        if (added.minY < y) fit = Math.min(fit, y / (y - added.minY));
        if (added.maxY > y) fit = Math.min(fit, (height - y) / (added.maxY - y));
        if (fit * added.feature < 4) { message("Too close to the wall"); return; }
        if (fit < 1) added = makeBody(selected, x, y, added.size * Math.max(0, fit * 0.98));
        if (added.feature < 4) { message("Too close to the wall"); return; }
        added.vx = vx;
        added.vy = vy;
        int overlaps = 0;
        for (Body other : bodies) {
            Contact c = collide(added, other);
            if (c == null) continue;
            overlaps++;
            // 强制插入本身就是外部能量输入。这里加一个有限的分离冲量，产生挤飞效果。
            // 两体受到等大反向冲量，较轻的物体速度变化更大。
            double kick = Math.min(550, 180 + c.depth * 12);
            double impulse = kick / (added.invMass + other.invMass);
            other.vx += c.nx * impulse * other.invMass;
            other.vy += c.ny * impulse * other.invMass;
            added.vx -= c.nx * impulse * added.invMass;
            added.vy -= c.ny * impulse * added.invMass;
            limitSpeed(other);
        }
        limitSpeed(added);
        bodies.add(added);
        message(overlaps > 0 ? "Forced spawn: " + overlaps + " overlapping bodies pushed apart" : "");
    }

    private void message(String text) { status = text; statusTime = 1.6; }

    private Body makeBody(Kind kind, double x, double y, double size) {
        Color color = Color.getHSBColor(random.nextFloat(), 0.55f + random.nextFloat() * 0.3f, 0.95f);
        if (kind == Kind.CIRCLE) return new Body(kind, x, y, size, null, color);
        double[] vertices;
        switch (kind) {
            case TRIANGLE -> vertices = new double[]{0, -size, size * 0.8660254, size * 0.5, -size * 0.8660254, size * 0.5};
            case SQUARE -> vertices = new double[]{-size, -size, size, -size, size, size, -size, size};
            case RECTANGLE -> vertices = new double[]{-size * 1.5, -size * 0.65, size * 1.5, -size * 0.65,
                    size * 1.5, size * 0.65, -size * 1.5, size * 0.65};
            case SEMICIRCLE -> {
                // 凸多边形：上半圆弧的 16 条边，加一条直径。质心稍后自动计算。
                vertices = new double[34];
                for (int i = 0; i <= 16; i++) {
                    double angle = Math.PI + Math.PI * i / 16;
                    vertices[i * 2] = Math.cos(angle) * size;
                    vertices[i * 2 + 1] = Math.sin(angle) * size;
                }
            }
            default -> throw new IllegalArgumentException("Unknown shape");
        }
        return new Body(kind, x, y, size, vertices, color);
    }

    private ArrayList<Contact> findContacts(double w, double h, double lv, double rv, double tv, double bv) {
        ArrayList<Contact> contacts = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            Body a = bodies.get(i);
            for (int j = i + 1; j < bodies.size(); j++) {
                Contact c = collide(a, bodies.get(j));
                if (c != null) contacts.add(c);
            }
            // n 指向墙；相对速度按“墙 - 物体”计算。
            if (a.minX < 0) contacts.add(wallContact(a, -1, 0, -a.minX, lv, tv));
            if (a.maxX > w) contacts.add(wallContact(a, 1, 0, a.maxX - w, rv, tv));
            if (a.minY < 0) contacts.add(wallContact(a, 0, -1, -a.minY, lv, tv));
            if (a.maxY > h) contacts.add(wallContact(a, 0, 1, a.maxY - h, lv, bv));
        }
        return contacts;
    }

    private static Contact wallContact(Body a, double nx, double ny, double depth, double vx, double vy) {
        Contact c = new Contact(a, null, nx, ny, depth, vx, vy);
        if (a.circle()) {
            c.addPoint(a.x + nx * a.size, a.y + ny * a.size);
        } else {
            double extreme = -Double.MAX_VALUE;
            for (int i = 0; i < a.wx.length; i++) extreme = Math.max(extreme, a.wx[i] * nx + a.wy[i] * ny);
            // 平边接触保留两个端点，角接触则只保留一个点。
            for (int i = 0; i < a.wx.length; i++) {
                if (extreme - (a.wx[i] * nx + a.wy[i] * ny) < 0.02) c.addPoint(a.wx[i], a.wy[i]);
            }
        }
        return c;
    }

    /** 圆-圆解析检测；其余凸形状使用 SAT。法线总是从 a 指向 b。 */
    private static Contact collide(Body a, Body b) {
        if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) return null;
        if (a.circle() && b.circle()) {
            double dx = b.x - a.x, dy = b.y - a.y, sum = a.size + b.size;
            double distance = Math.hypot(dx, dy);
            if (distance >= sum) return null;
            double nx = distance > EPSILON ? dx / distance : 1;
            double ny = distance > EPSILON ? dy / distance : 0;
            Contact c = new Contact(a, b, nx, ny, sum - distance, 0, 0);
            c.addPoint((a.x + nx * a.size + b.x - nx * b.size) / 2,
                    (a.y + ny * a.size + b.y - ny * b.size) / 2);
            return c;
        }
        Axis best = new Axis();
        if (!a.circle() && !testEdges(a, a, b, best)) return null;
        if (!b.circle() && !testEdges(b, a, b, best)) return null;
        if (a.circle() || b.circle()) {
            Body circle = a.circle() ? a : b, polygon = a.circle() ? b : a;
            double nearest = Double.MAX_VALUE, dx = 0, dy = 0;
            for (int i = 0; i < polygon.wx.length; i++) {
                double tx = polygon.wx[i] - circle.x, ty = polygon.wy[i] - circle.y;
                double d = tx * tx + ty * ty;
                if (d < nearest) { nearest = d; dx = tx; dy = ty; }
            }
            double length = Math.hypot(dx, dy);
            if (length > EPSILON && !testAxis(a, b, dx / length, dy / length, best)) return null;
        }
        Contact c = new Contact(a, b, best.nx, best.ny, best.depth, 0, 0);
        if (a.circle() || b.circle()) {
            Body circle = a.circle() ? a : b, polygon = a.circle() ? b : a;
            double[] p = closestPoint(polygon, circle.x, circle.y);
            c.addPoint(p[0], p[1]);
        } else {
            polygonContacts(c);
        }
        return c;
    }

    private static class Axis { double nx, ny, depth = Double.MAX_VALUE; }

    private static boolean testEdges(Body polygon, Body a, Body b, Axis best) {
        for (int i = 0; i < polygon.wx.length; i++) {
            int j = (i + 1) % polygon.wx.length;
            double ex = polygon.wx[j] - polygon.wx[i], ey = polygon.wy[j] - polygon.wy[i];
            double len = Math.hypot(ex, ey);
            if (!testAxis(a, b, ey / len, -ex / len, best)) return false;
        }
        return true;
    }

    private static boolean testAxis(Body a, Body b, double nx, double ny, Axis best) {
        double amin, amax, bmin, bmax;
        if (a.circle()) { double p = a.x * nx + a.y * ny; amin = p - a.size; amax = p + a.size; }
        else {
            amin = Double.MAX_VALUE; amax = -Double.MAX_VALUE;
            for (int i = 0; i < a.wx.length; i++) { double p = a.wx[i] * nx + a.wy[i] * ny; amin = Math.min(amin, p); amax = Math.max(amax, p); }
        }
        if (b.circle()) { double p = b.x * nx + b.y * ny; bmin = p - b.size; bmax = p + b.size; }
        else {
            bmin = Double.MAX_VALUE; bmax = -Double.MAX_VALUE;
            for (int i = 0; i < b.wx.length; i++) { double p = b.wx[i] * nx + b.wy[i] * ny; bmin = Math.min(bmin, p); bmax = Math.max(bmax, p); }
        }
        if (amax <= bmin || bmax <= amin) return false;
        // 取两个可能分离方向中较短的一个，能正确处理完全包含的情况。
        double positive = amax - bmin, negative = bmax - amin;
        double depth = Math.min(positive, negative);
        if (depth < best.depth) {
            best.depth = depth;
            best.nx = positive <= negative ? nx : -nx;
            best.ny = positive <= negative ? ny : -ny;
        }
        return true;
    }

    private static double[] closestPoint(Body p, double x, double y) {
        double nearest = Double.MAX_VALUE, px = p.x, py = p.y;
        for (int i = 0; i < p.wx.length; i++) {
            int j = (i + 1) % p.wx.length;
            double ex = p.wx[j] - p.wx[i], ey = p.wy[j] - p.wy[i];
            double t = clamp(((x - p.wx[i]) * ex + (y - p.wy[i]) * ey) / (ex * ex + ey * ey), 0, 1);
            double qx = p.wx[i] + t * ex, qy = p.wy[i] + t * ey;
            double d = (x - qx) * (x - qx) + (y - qy) * (y - qy);
            if (d < nearest) { nearest = d; px = qx; py = qy; }
        }
        return new double[]{px, py};
    }

    private static int bestFace(Body p, double nx, double ny) {
        int face = 0; double best = -Double.MAX_VALUE;
        for (int i = 0; i < p.wx.length; i++) {
            int j = (i + 1) % p.wx.length;
            double ex = p.wx[j] - p.wx[i], ey = p.wy[j] - p.wy[i];
            double dot = (ey * nx - ex * ny) / Math.hypot(ex, ey);
            if (dot > best) { best = dot; face = i; }
        }
        return face;
    }

    private static double faceAlignment(Body p, int face, double nx, double ny) {
        int next = (face + 1) % p.wx.length;
        double ex = p.wx[next] - p.wx[face], ey = p.wy[next] - p.wy[face];
        return (ey * nx - ex * ny) / Math.hypot(ex, ey);
    }

    /** 裁剪入射边，获得最多两个接触点；不能用两物体中心代替接触位置。 */
    private static void polygonContacts(Contact c) {
        int faceA = bestFace(c.a, c.nx, c.ny), faceB = bestFace(c.b, -c.nx, -c.ny);
        boolean useA = faceAlignment(c.a, faceA, c.nx, c.ny) >= faceAlignment(c.b, faceB, -c.nx, -c.ny);
        Body ref = useA ? c.a : c.b, inc = useA ? c.b : c.a;
        int face = useA ? faceA : faceB, next = (face + 1) % ref.wx.length;
        double ex = ref.wx[next] - ref.wx[face], ey = ref.wy[next] - ref.wy[face];
        double len = Math.hypot(ex, ey), tx = ex / len, ty = ey / len;
        double nx = ty, ny = -tx;
        int incident = bestFace(inc, -nx, -ny), incidentNext = (incident + 1) % inc.wx.length;
        double px = inc.wx[incident], py = inc.wy[incident];
        double qx = inc.wx[incidentNext], qy = inc.wy[incidentNext];
        double low = ref.wx[face] * tx + ref.wy[face] * ty;
        double high = ref.wx[next] * tx + ref.wy[next] * ty;
        double p = px * tx + py * ty, delta = (qx - px) * tx + (qy - py) * ty;
        double lo = 0, hi = 1;
        if (Math.abs(delta) < EPSILON) {
            if (p < low - EPSILON || p > high + EPSILON) return;
        } else {
            double t1 = (low - p) / delta, t2 = (high - p) / delta;
            lo = Math.max(0, Math.min(t1, t2));
            hi = Math.min(1, Math.max(t1, t2));
            if (lo > hi) return;
        }
        addClippedPoint(c, ref, face, nx, ny, px + (qx - px) * lo, py + (qy - py) * lo);
        if (hi - lo > 1e-6) addClippedPoint(c, ref, face, nx, ny, px + (qx - px) * hi, py + (qy - py) * hi);
    }

    private static void addClippedPoint(Contact c, Body ref, int face, double nx, double ny, double x, double y) {
        double separation = (x - ref.wx[face]) * nx + (y - ref.wy[face]) * ny;
        if (separation <= 0.01) c.addPoint(x - nx * separation * 0.5, y - ny * separation * 0.5);
    }

    /** 接触点的累计冲量在同一子步内迭代，防止每次迭代都重复施加弹性反弹。 */
    private static class Contact {
        final Body a, b;
        final double nx, ny, depth, wallVX, wallVY;
        final ArrayList<Point> points = new ArrayList<>(2);
        Contact(Body a, Body b, double nx, double ny, double depth, double wallVX, double wallVY) {
            this.a = a; this.b = b; this.nx = nx; this.ny = ny;
            this.depth = depth; this.wallVX = wallVX; this.wallVY = wallVY;
        }
        void addPoint(double x, double y) {
            if (points.size() < 2) points.add(new Point(x, y));
        }
        double relativeVelocity(Point p, double ax, double ay) {
            double avx = a.vx - a.spin * (p.y - a.y), avy = a.vy + a.spin * (p.x - a.x);
            double bvx = b == null ? wallVX : b.vx - b.spin * (p.y - b.y);
            double bvy = b == null ? wallVY : b.vy + b.spin * (p.x - b.x);
            return (bvx - avx) * ax + (bvy - avy) * ay;
        }
        double effectiveMass(Point p, double ax, double ay) {
            double ra = (p.x - a.x) * ay - (p.y - a.y) * ax;
            double result = a.invMass + ra * ra * a.invInertia;
            if (b != null) {
                double rb = (p.x - b.x) * ay - (p.y - b.y) * ax;
                result += b.invMass + rb * rb * b.invInertia;
            }
            return result;
        }
        void prepare() {
            for (Point p : points) {
                double vn = relativeVelocity(p, nx, ny);
                p.bounce = vn < -80 ? -RESTITUTION * vn : 0;
            }
        }
        void solveVelocity() {
            for (Point p : points) {
                double lambda = (p.bounce - relativeVelocity(p, nx, ny)) / effectiveMass(p, nx, ny);
                double old = p.normalImpulse;
                p.normalImpulse = Math.max(0, old + lambda);
                applyImpulse(p, nx * (p.normalImpulse - old), ny * (p.normalImpulse - old));
                double tx = -ny, ty = nx;
                lambda = -relativeVelocity(p, tx, ty) / effectiveMass(p, tx, ty);
                old = p.tangentImpulse;
                p.tangentImpulse = clamp(old + lambda, -FRICTION * p.normalImpulse, FRICTION * p.normalImpulse);
                applyImpulse(p, tx * (p.tangentImpulse - old), ty * (p.tangentImpulse - old));
            }
        }
        void applyImpulse(Point p, double ix, double iy) {
            a.vx -= ix * a.invMass; a.vy -= iy * a.invMass;
            a.spin -= ((p.x - a.x) * iy - (p.y - a.y) * ix) * a.invInertia;
            if (b != null) {
                b.vx += ix * b.invMass; b.vy += iy * b.invMass;
                b.spin += ((p.x - b.x) * iy - (p.y - b.y) * ix) * b.invInertia;
            }
        }
        void correctPosition() {
            if (depth <= 0.03) return;
            // 多点接触分摊位置修正；限制一次修正幅度，强制重叠逐步排开。
            for (Point p : points) {
                double impulse = Math.min(12, (depth - 0.03) * 0.55) / points.size() / effectiveMass(p, nx, ny);
                double ra = (p.x - a.x) * ny - (p.y - a.y) * nx;
                a.x -= nx * impulse * a.invMass;
                a.y -= ny * impulse * a.invMass;
                a.angle -= ra * impulse * a.invInertia;
                if (b != null) {
                    double rb = (p.x - b.x) * ny - (p.y - b.y) * nx;
                    b.x += nx * impulse * b.invMass;
                    b.y += ny * impulse * b.invMass;
                    b.angle += rb * impulse * b.invInertia;
                }
            }
            a.sync();
            if (b != null) b.sync();
        }
    }

    private static class Point {
        final double x, y;
        double normalImpulse, tangentImpulse, bounce;
        Point(double x, double y) { this.x = x; this.y = y; }
    }

    private static class Body {
        final Kind kind;
        final Color color;
        final double size, invMass, invInertia, boundRadius, feature;
        final double[] localX, localY, wx, wy;
        double x, y, vx, vy, angle, spin, minX, minY, maxX, maxY;
        Body(Kind kind, double x, double y, double size, double[] vertices, Color color) {
            this.kind = kind; this.x = x; this.y = y; this.size = size; this.color = color;
            if (vertices == null) {
                localX = localY = wx = wy = null;
                double mass = Math.PI * size * size;
                invMass = 1 / mass;
                invInertia = 2 / (mass * size * size);
                boundRadius = feature = size;
            } else {
                int n = vertices.length / 2;
                localX = new double[n]; localY = new double[n]; wx = new double[n]; wy = new double[n];
                double twiceArea = 0, cx = 0, cy = 0;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    double cross = vertices[2 * i] * vertices[2 * j + 1] - vertices[2 * j] * vertices[2 * i + 1];
                    twiceArea += cross;
                    cx += (vertices[2 * i] + vertices[2 * j]) * cross;
                    cy += (vertices[2 * i + 1] + vertices[2 * j + 1]) * cross;
                }
                cx /= 3 * twiceArea; cy /= 3 * twiceArea;
                double bound = 0;
                for (int i = 0; i < n; i++) {
                    // 半圆也绕真实面积质心旋转，不绕包围盒中心旋转。
                    localX[i] = vertices[2 * i] - cx; localY[i] = vertices[2 * i + 1] - cy;
                    bound = Math.max(bound, Math.hypot(localX[i], localY[i]));
                }
                double inertia = 0, smallest = Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    double cross = localX[i] * localY[j] - localX[j] * localY[i];
                    inertia += cross * (localX[i] * localX[i] + localX[i] * localX[j] + localX[j] * localX[j]
                            + localY[i] * localY[i] + localY[i] * localY[j] + localY[j] * localY[j]);
                    double ex = localX[j] - localX[i], ey = localY[j] - localY[i];
                    smallest = Math.min(smallest, Math.abs(ex * -localY[i] - ey * -localX[i]) / Math.hypot(ex, ey));
                }
                invMass = 2 / Math.abs(twiceArea);
                invInertia = 12 / Math.abs(inertia);
                boundRadius = bound; feature = smallest;
            }
            sync();
        }
        boolean circle() { return localX == null; }
        void sync() {
            if (circle()) { minX = x - size; maxX = x + size; minY = y - size; maxY = y + size; return; }
            double cos = Math.cos(angle), sin = Math.sin(angle);
            minX = minY = Double.MAX_VALUE; maxX = maxY = -Double.MAX_VALUE;
            for (int i = 0; i < localX.length; i++) {
                wx[i] = x + localX[i] * cos - localY[i] * sin;
                wy[i] = y + localX[i] * sin + localY[i] * cos;
                minX = Math.min(minX, wx[i]); maxX = Math.max(maxX, wx[i]);
                minY = Math.min(minY, wy[i]); maxY = Math.max(maxY, wy[i]);
            }
        }
    }

    private static void contain(Body b, double w, double h, double lv, double rv, double tv, double bv) {
        // 最终边界保护。容器比物体还小时只能居中裁切，不能满足不可穿透约束。
        if (b.maxX - b.minX > w) { b.x += w / 2 - (b.minX + b.maxX) / 2; b.vx = (lv + rv) / 2; }
        else if (b.minX < 0) b.x -= b.minX;
        else if (b.maxX > w) b.x -= b.maxX - w;
        if (b.maxY - b.minY > h) { b.y += h / 2 - (b.minY + b.maxY) / 2; b.vy = (tv + bv) / 2; }
        else if (b.minY < 0) b.y -= b.minY;
        else if (b.maxY > h) b.y -= b.maxY - h;
        b.sync();
    }

    private static void limitSpeed(Body b) {
        double speed = Math.hypot(b.vx, b.vy);
        if (speed > MAX_SPEED) { b.vx *= MAX_SPEED / speed; b.vy *= MAX_SPEED / speed; }
        b.spin = clamp(b.spin, -MAX_SPIN, MAX_SPIN);
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    @Override
    public void render(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            Rectangle clip = g.getClipBounds();
            if (clip != null) g.fillRect(clip.x, clip.y, clip.width, clip.height);
            else g.fillRect(0, 0, (int) width, (int) height);
            for (Body b : bodies) {
                Shape shape;
                if (b.circle()) shape = new Ellipse2D.Double(b.x - b.size, b.y - b.size, b.size * 2, b.size * 2);
                else {
                    Path2D path = new Path2D.Double();
                    path.moveTo(b.wx[0], b.wy[0]);
                    for (int i = 1; i < b.wx.length; i++) path.lineTo(b.wx[i], b.wy[i]);
                    path.closePath(); shape = path;
                }
                g.setColor(b.color); g.fill(shape);
                g.setColor(b.color.darker()); g.draw(shape);
                g.setColor(new Color(255, 255, 255, 180));
                double mx = b.x + Math.cos(b.angle) * b.feature * 0.55;
                double my = b.y + Math.sin(b.angle) * b.feature * 0.55;
                g.drawLine((int) b.x, (int) b.y, (int) mx, (int) my);
                g.fill(new Ellipse2D.Double(b.x - 1.5, b.y - 1.5, 3, 3));
            }
            g.setColor(new Color(0, 0, 0, 175));
            g.fillRoundRect(8, 7, 710, 112, 10, 10);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(235, 240, 250));
            g.drawString("1 Circle | 2 Triangle | 3 Square | 4 Rectangle | 5 Semicircle", 15, 25);
            g.drawString("Click/hold or Space: FORCE spawn | C: clear | R: reset balls", 15, 43);
            g.drawString("Selected: " + selected.label + " | Bodies: " + bodies.size() + "/" + MAX_BODIES, 15, 61);
            g.setColor(new Color(140, 255, 165));
            g.drawString(statsReady ? "Update FPS: %.1f | Max interval: %.1f ms".formatted(updateFps, shownIntervalMs)
                    : "Update FPS: -- | collecting...", 15, 79);
            g.drawString("Update cost: avg %.2f ms | max %.2f ms".formatted(shownMeanCostMs, shownMaxCostMs), 15, 97);
            if (statusTime > 0 && !status.isEmpty()) {
                g.setColor(new Color(255, 210, 110)); g.drawString(status, 15, 136);
            }
        } finally { g.dispose(); }
    }

    static void main() {
        runScene(new TestScene2());
    }
}
