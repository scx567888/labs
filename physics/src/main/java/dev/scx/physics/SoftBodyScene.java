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
 * 质点-弹簧软体沙盒，无第三方依赖。
 * 每个软体 = 16 个外圈质点 + 8 个内圈质点 + 中心质点。
 * 弹簧使用 Hooke 力和沿弹簧方向的阻尼；面积压力抑制塌缩。
 * 三角形防翻转和弹簧极限长度是极端挤压下的数值保护，不是刚体约束。
 * 坐标相对客户区，速度相对桌面。Runner 需先调用 start，再调用 update/render。
 */
public class SoftBodyScene implements Scene {
    private static final int OUTER = 16, INNER = 8, CENTER = 24;
    private static final int MAX_BODIES = 32;
    private static final double GRAVITY = 1400;
    private static final double RESTITUTION = 0.06;
    private static final double FRICTION = 0.55;
    private static final double MAX_SPEED = 2000;
    private static final double DAMPING_RATIO = 0.13;
    private static final int CONTACT_ITERATIONS = 3;
    private static final Color BACKGROUND = new Color(19, 25, 37);
    private static final double[] FREQUENCIES = {32, 48, 72};
    private static final String[] MATERIAL_NAMES = {"Jelly", "Rubber", "Firm"};

    private final ArrayList<SoftBody> bodies = new ArrayList<>();
    private final Random random = new Random();
    private double width, height, windowX, windowY;
    private boolean skeleton, nodesOnly, outlinesOnly;
    private int material = 1, nextId;
    private boolean prevB, prevN, prevV, prevC, prevR, prevMouse, prevSpace;
    private Particle grabbed;
    private double grabX, grabY, spawnCooldown;
    private String status = "";
    private double statusTime;
    private long lastNanos, statsStart;
    private int updateCount;
    private double updateFps, maxInterval, shownInterval, totalCost, maxCost, shownCost, shownMaxCost;
    private boolean statsReady;

    @Override
    public void start(SceneContext context) {
        width = context.viewport().width(); height = context.viewport().height();
        if (width <= 0 || height <= 0) throw new IllegalStateException("start requires a positive viewport size");
        windowX = context.window().x(); windowY = context.window().y();
        bodies.clear(); nextId = 0; grabbed = null;
        skeleton = nodesOnly = outlinesOnly = false;
        prevB = prevN = prevV = prevC = prevR = prevMouse = prevSpace = false;
        material = 1; spawnCooldown = statusTime = 0; status = "";
        updateCount = 0; updateFps = maxInterval = shownInterval = totalCost = maxCost = shownCost = shownMaxCost = 0;
        statsReady = false;
        seedBodies();
        lastNanos = statsStart = System.nanoTime();
    }

    @Override
    public void update(SceneContext context) {
        long now = System.nanoTime();
        double elapsed = Math.max(1e-6, (now - lastNanos) * 1e-9);
        lastNanos = now;
        updateCount++;
        maxInterval = Math.max(maxInterval, elapsed * 1000);
        statusTime = Math.max(0, statusTime - elapsed);
        if (context.viewport().width() > 0 && context.viewport().height() > 0) {
            advance(context, Math.min(elapsed, 0.04), elapsed);
        }
        double cost = (System.nanoTime() - now) * 1e-6;
        totalCost += cost; maxCost = Math.max(maxCost, cost);
        long interval = now - statsStart;
        if (interval >= 1_000_000_000L) {
            updateFps = updateCount * 1e9 / interval;
            shownInterval = maxInterval; shownCost = totalCost / updateCount; shownMaxCost = maxCost;
            updateCount = 0; maxInterval = totalCost = maxCost = 0;
            statsStart = now; statsReady = true;
        }
    }

    private void seedBodies() {
        double cell = Math.min(110, Math.min(width / 5, height / 3));
        if (cell < 36) return;
        int columns = Math.max(1, (int) (width / cell));
        int rows = Math.max(1, (int) (height * 0.6 / cell));
        for (int row = 0; row < rows && bodies.size() < 21; row++) {
            for (int col = 0; col < columns && bodies.size() < 21; col++) {
                double radius = cell * (0.28 + random.nextDouble() * 0.16);
                bodies.add(new SoftBody(nextId++, (col + 0.5) * cell,
                        height - (row + 0.5) * cell, radius, nextColor()));
            }
        }
    }

    private void advance(SceneContext context, double dt, double realDt) {
        Input input = context.input();
        handleKeys(input);
        double newWidth = context.viewport().width(), newHeight = context.viewport().height();
        double dx = context.window().x() - windowX, dy = context.window().y() - windowY;
        double dw = newWidth - width, dh = newHeight - height;
        double lv = dx / realDt, tv = dy / realDt;
        double rv = (dx + dw) / realDt, bv = (dy + dh) / realDt;
        updateGrab(input);
        double speed = 0;
        for (SoftBody body : bodies) for (Particle p : body.particles) speed = Math.max(speed, Math.hypot(p.vx, p.vy));
        double travel = Math.max(Math.max(Math.abs(dx), Math.abs(dy)),
                Math.max(Math.abs(dx + dw), Math.abs(dy + dh)));
        // 小步长保证显式弹簧稳定；高速移动时进一步细分，防止穿过边界。
        int steps = Math.min(128, Math.max(1, Math.max((int) Math.ceil(dt * 600),
                (int) Math.ceil((speed * dt + travel) / 2.5))));
        double h = dt / steps, omega = FREQUENCIES[material];
        double oldW = width, oldH = height;
        for (int step = 1; step <= steps; step++) {
            double w = oldW + dw * step / steps, heightNow = oldH + dh * step / steps;
            for (SoftBody body : bodies) {
                for (Particle p : body.particles) { p.fx = 0; p.fy = p.mass * GRAVITY; }
                for (Spring spring : body.springs) spring.addForce(omega);
                body.addAreaPressure(omega);
            }
            if (grabbed != null) {
                // 鼠标牵引也是弹簧，不把质点直接瞬移到鼠标位置。
                double gx = grabX, gy = grabY;
                double ax = (gx - grabbed.x) * 2800 - (grabbed.vx - lv) * 75;
                double ay = (gy - grabbed.y) * 2800 - (grabbed.vy - tv) * 75;
                double length = Math.hypot(ax, ay);
                double scale = length > 35000 ? 35000 / length : 1;
                grabbed.fx += grabbed.mass * ax * scale;
                grabbed.fy += grabbed.mass * ay * scale;
            }
            for (SoftBody body : bodies) {
                for (Particle p : body.particles) {
                    p.vx += p.fx * p.invMass * h;
                    p.vy += p.fy * p.invMass * h;
                    limitVelocity(p);
                    p.x += p.vx * h - dx / steps;
                    p.y += p.vy * h - dy / steps;
                }
                body.guardMesh();
                body.bounds();
            }
            for (int iteration = 0; iteration < CONTACT_ITERATIONS; iteration++) {
                for (int i = 0; i < bodies.size(); i++) {
                    SoftBody a = bodies.get(i);
                    for (int j = i + 1; j < bodies.size(); j++) {
                        SoftBody b = bodies.get(j);
                        if (!a.overlaps(b)) continue;
                        collideBodies(a, b);
                        collideBodies(b, a);
                        a.bounds(); b.bounds();
                    }
                }
                for (SoftBody body : bodies) {
                    body.guardMesh();
                    for (Particle p : body.particles) collideWalls(p, body.skin, w, heightNow, lv, rv, tv, bv);
                    body.bounds();
                }
            }
            for (SoftBody body : bodies) for (Particle p : body.particles) limitVelocity(p);
        }
        width = newWidth; height = newHeight;
        windowX = context.window().x(); windowY = context.window().y();
        handleSpawn(input, realDt, lv, tv);
    }

    private void handleKeys(Input input) {
        boolean b = input.keyDown(KeyEvent.VK_B), n = input.keyDown(KeyEvent.VK_N), v = input.keyDown(KeyEvent.VK_V);
        boolean c = input.keyDown(KeyEvent.VK_C), r = input.keyDown(KeyEvent.VK_R);
        if (b && !prevB) skeleton = !skeleton;
        if (n && !prevN) nodesOnly = !nodesOnly;
        if (v && !prevV) outlinesOnly = !outlinesOnly;
        if (c && !prevC) { bodies.clear(); grabbed = null; }
        if (r && !prevR) { bodies.clear(); grabbed = null; seedBodies(); }
        for (int i = 0; i < 3; i++) {
            if (input.keyDown(KeyEvent.VK_1 + i) || input.keyDown(KeyEvent.VK_NUMPAD1 + i)) material = i;
        }
        prevB = b; prevN = n; prevV = v; prevC = c; prevR = r;
    }

    private void updateGrab(Input input) {
        if (!input.mouseDown(MouseEvent.BUTTON3)) { grabbed = null; return; }
        grabX = input.mouseX(); grabY = input.mouseY();
        if (grabbed != null) return;
        double distance = 45 * 45;
        for (SoftBody body : bodies) for (Particle p : body.particles) {
            double d = (p.x - grabX) * (p.x - grabX) + (p.y - grabY) * (p.y - grabY);
            if (d < distance) { distance = d; grabbed = p; }
        }
    }

    private void handleSpawn(Input input, double dt, double vx, double vy) {
        boolean mouse = input.mouseDown(MouseEvent.BUTTON1), space = input.keyDown(KeyEvent.VK_SPACE);
        spawnCooldown -= dt;
        if ((mouse && !prevMouse) || (space && !prevSpace) || ((mouse || space) && spawnCooldown <= 0)) {
            spawn(mouse ? input.mouseX() : width / 2, mouse ? input.mouseY() : 80, vx, vy);
            spawnCooldown = 0.3;
        }
        prevMouse = mouse; prevSpace = space;
    }

    private void spawn(double x, double y, double vx, double vy) {
        if (bodies.size() >= MAX_BODIES) { message("Soft-body limit reached (C to clear)"); return; }
        double available = Math.min(Math.min(x, width - x), Math.min(y, height - y)) - 4;
        double radius = Math.min(25 + random.nextDouble() * 30, available);
        if (radius < 16) { message("Too close to the wall"); return; }
        SoftBody added = new SoftBody(nextId++, x, y, radius, nextColor());
        for (Particle p : added.particles) { p.vx = vx; p.vy = vy; }
        bodies.add(added);
        // 强制生成可能造成完整轮廓重叠。只在生成瞬间用整体平移排开深重叠，
        // 保留各软体内部弹簧长度；随后完全由质点/边碰撞和弹簧求解。
        // 不随机换生成位置；排开动作可能使生成后的中心离开点击点。
        for (int iteration = 0; iteration < 6; iteration++) {
            for (SoftBody other : bodies) {
                if (other == added) continue;
                separateSpawn(added, other, iteration == 0);
            }
        }
        for (SoftBody body : bodies) {
            for (Particle p : body.particles) {
                collideWalls(p, body.skin, width, height, vx, vx, vy, vy);
                limitVelocity(p);
            }
            body.bounds();
        }
        message("Created " + (int) radius + " px soft body (25 particles)");
    }

    private static void separateSpawn(SoftBody a, SoftBody b, boolean kick) {
        double ax = a.centerX(), ay = a.centerY(), bx = b.centerX(), by = b.centerY();
        double dx = bx - ax, dy = by - ay, length = Math.hypot(dx, dy);
        double target = a.radius + b.radius + a.skin + b.skin;
        if (length >= target || !a.overlaps(b)) return;
        double nx, ny;
        if (length < 1e-6) {
            double angle = (a.id * 0.61803398875 + b.id * 0.38196601125) * Math.PI * 2;
            nx = Math.cos(angle); ny = Math.sin(angle);
        } else { nx = dx / length; ny = dy / length; }
        double wa = b.totalMass / (a.totalMass + b.totalMass), wb = 1 - wa;
        double move = (target - length) * 0.75;
        a.translate(-nx * move * wa, -ny * move * wa);
        b.translate(nx * move * wb, ny * move * wb);
        if (kick) {
            for (Particle p : a.particles) { p.vx -= nx * 160 * wa; p.vy -= ny * 160 * wa; }
            for (Particle p : b.particles) { p.vx += nx * 160 * wb; p.vy += ny * 160 * wb; }
        }
    }

    private static class Particle {
        double x, y, vx, vy, fx, fy;
        final double mass, invMass;
        Particle(double x, double y, double mass) { this.x = x; this.y = y; this.mass = mass; invMass = 1 / mass; }
    }

    private static class Spring {
        final Particle a, b;
        final double rest, strength;
        final boolean surface;
        Spring(Particle a, Particle b, double strength, boolean surface) {
            this.a = a; this.b = b; this.strength = strength; this.surface = surface;
            rest = Math.hypot(b.x - a.x, b.y - a.y);
        }
        void addForce(double omega) {
            double dx = b.x - a.x, dy = b.y - a.y, length = Math.hypot(dx, dy);
            if (length < 1e-8) return;
            double nx = dx / length, ny = dy / length;
            double reducedMass = 1 / (a.invMass + b.invMass);
            double k = reducedMass * omega * omega * strength;
            double damping = 2 * DAMPING_RATIO * Math.sqrt(k * reducedMass);
            double relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
            // F = k (length - rest) + c * relativeSpeed，作用在 a/b 上的力等大反向。
            double force = k * (length - rest) + damping * relative;
            a.fx += force * nx; a.fy += force * ny;
            b.fx -= force * nx; b.fy -= force * ny;
        }
        void guardLength() {
            double dx = b.x - a.x, dy = b.y - a.y, length = Math.hypot(dx, dy);
            if (length < 1e-8) return;
            double target = clamp(length, rest * 0.35, rest * 1.8);
            if (target == length) return;
            double correction = (length - target) * 0.35 / (a.invMass + b.invMass);
            double nx = dx / length, ny = dy / length;
            a.x += nx * correction * a.invMass; a.y += ny * correction * a.invMass;
            b.x -= nx * correction * b.invMass; b.y -= ny * correction * b.invMass;
        }
    }

    private static class Triangle {
        final Particle a, b, c;
        final double restArea;
        Triangle(Particle a, Particle b, Particle c) { this.a = a; this.b = b; this.c = c; restArea = area(); }
        double area() { return ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) * 0.5; }
        void preventFlip() {
            double current = area(), minimum = restArea * 0.15;
            if (current >= minimum) return;
            double ax = (b.y - c.y) * 0.5, ay = (c.x - b.x) * 0.5;
            double bx = (c.y - a.y) * 0.5, by = (a.x - c.x) * 0.5;
            double cx = (a.y - b.y) * 0.5, cy = (b.x - a.x) * 0.5;
            double inverseMass = a.invMass * (ax * ax + ay * ay) + b.invMass * (bx * bx + by * by)
                    + c.invMass * (cx * cx + cy * cy);
            if (inverseMass < 1e-9) return;
            double lambda = (minimum - current) * 0.65 / inverseMass;
            // 限制极端挤压时单次位移，避免产生数值爆炸。
            double maxMove = Math.max(Math.hypot(ax, ay) * a.invMass,
                    Math.max(Math.hypot(bx, by) * b.invMass, Math.hypot(cx, cy) * c.invMass)) * lambda;
            if (maxMove > 4) lambda *= 4 / maxMove;
            a.x += ax * lambda * a.invMass; a.y += ay * lambda * a.invMass;
            b.x += bx * lambda * b.invMass; b.y += by * lambda * b.invMass;
            c.x += cx * lambda * c.invMass; c.y += cy * lambda * c.invMass;
        }
    }

    private static class SoftBody {
        final int id;
        final Particle[] particles = new Particle[25];
        final ArrayList<Spring> springs = new ArrayList<>();
        final ArrayList<Triangle> triangles = new ArrayList<>();
        final double radius, skin, restArea, totalMass;
        final Color color;
        double minX, minY, maxX, maxY;
        SoftBody(int id, double x, double y, double radius, Color color) {
            this.id = id; this.radius = radius; this.color = color;
            skin = clamp(radius * 0.065, 1.5, 3.5);
            totalMass = Math.PI * radius * radius / 100;
            double mass = totalMass / particles.length;
            for (int i = 0; i < OUTER; i++) {
                double angle = Math.PI * 2 * i / OUTER;
                particles[i] = new Particle(x + Math.cos(angle) * radius, y + Math.sin(angle) * radius, mass);
            }
            for (int i = 0; i < INNER; i++) {
                double angle = Math.PI * 2 * i / INNER;
                particles[OUTER + i] = new Particle(x + Math.cos(angle) * radius * 0.48, y + Math.sin(angle) * radius * 0.48, mass);
            }
            particles[CENTER] = new Particle(x, y, mass);
            boolean[][] connected = new boolean[25][25];
            for (int i = 0; i < OUTER; i++) {
                addSpring(i, (i + 1) % OUTER, 1.4, true, connected);
                addSpring(i, (i + 2) % OUTER, 0.4, false, connected);
                addSpring(i, OUTER + i / 2, 1, false, connected);
                addSpring(i, OUTER + (i / 2 + 1) % INNER, 1, false, connected);
            }
            for (int i = 0; i < INNER; i++) {
                int next = (i + 1) % INNER, oi = i * 2;
                addSpring(OUTER + i, OUTER + next, 1, false, connected);
                addSpring(OUTER + i, OUTER + (i + 2) % INNER, 0.5, false, connected);
                addSpring(CENTER, OUTER + i, 1.2, false, connected);
                triangles.add(new Triangle(particles[CENTER], particles[OUTER + i], particles[OUTER + next]));
                triangles.add(new Triangle(particles[OUTER + i], particles[oi], particles[(oi + 1) % OUTER]));
                triangles.add(new Triangle(particles[OUTER + i], particles[(oi + 1) % OUTER], particles[OUTER + next]));
                triangles.add(new Triangle(particles[OUTER + next], particles[(oi + 1) % OUTER], particles[(oi + 2) % OUTER]));
            }
            restArea = area();
            bounds();
        }
        void addSpring(int a, int b, double strength, boolean surface, boolean[][] connected) {
            if (connected[a][b] || connected[b][a]) return;
            connected[a][b] = true;
            springs.add(new Spring(particles[a], particles[b], strength, surface));
        }
        double area() {
            double result = 0;
            // 平移到一个局部参考点以减少较大桌面坐标下的相消误差。
            double x = particles[CENTER].x, y = particles[CENTER].y;
            for (int i = 0; i < OUTER; i++) {
                Particle a = particles[i], b = particles[(i + 1) % OUTER];
                result += (a.x - x) * (b.y - y) - (b.x - x) * (a.y - y);
            }
            return result * 0.5;
        }
        void addAreaPressure(double omega) {
            double error = clamp((restArea - area()) / restArea, -0.6, 0.8);
            double pressure = particles[0].mass * omega * omega * 2.5 * error;
            // 面积梯度：压力作用在整条边界，而不只作用在单个中心点。
            for (int i = 0; i < OUTER; i++) {
                Particle previous = particles[(i + OUTER - 1) % OUTER], next = particles[(i + 1) % OUTER];
                particles[i].fx += pressure * (next.y - previous.y) * 0.5;
                particles[i].fy += pressure * (previous.x - next.x) * 0.5;
            }
        }
        void guardMesh() {
            for (Spring spring : springs) spring.guardLength();
            for (Triangle triangle : triangles) triangle.preventFlip();
        }
        double centerX() { double value = 0; for (Particle p : particles) value += p.x; return value / particles.length; }
        double centerY() { double value = 0; for (Particle p : particles) value += p.y; return value / particles.length; }
        void translate(double x, double y) {
            for (Particle p : particles) { p.x += x; p.y += y; }
            bounds();
        }
        void bounds() {
            minX = minY = Double.MAX_VALUE; maxX = maxY = -Double.MAX_VALUE;
            for (Particle p : particles) {
                minX = Math.min(minX, p.x - skin); maxX = Math.max(maxX, p.x + skin);
                minY = Math.min(minY, p.y - skin); maxY = Math.max(maxY, p.y + skin);
            }
        }
        boolean overlaps(SoftBody other) {
            return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY;
        }
    }

    /** 外圈质点对另一软体的整条边界检测，避免只做点-点碰撞留下缝隙。 */
    private static void collideBodies(SoftBody a, SoftBody b) {
        double thickness = a.skin + b.skin;
        for (int i = 0; i < OUTER; i++) {
            Particle p = a.particles[i];
            if (p.x < b.minX - a.skin || p.x > b.maxX + a.skin || p.y < b.minY - a.skin || p.y > b.maxY + a.skin) continue;
            double bestDistance = Double.MAX_VALUE, bestT = 0, nearestX = 0, nearestY = 0;
            int nearestEdge = 0;
            boolean inside = false;
            for (int j = 0; j < OUTER; j++) {
                Particle q = b.particles[j], r = b.particles[(j + 1) % OUTER];
                double ex = r.x - q.x, ey = r.y - q.y, len2 = ex * ex + ey * ey;
                double t = len2 > 1e-10 ? clamp(((p.x - q.x) * ex + (p.y - q.y) * ey) / len2, 0, 1) : 0;
                double x = q.x + ex * t, y = q.y + ey * t;
                double d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y);
                if (d < bestDistance) { bestDistance = d; bestT = t; nearestEdge = j; nearestX = x; nearestY = y; }
                if ((q.y > p.y) != (r.y > p.y) && p.x < (r.x - q.x) * (p.y - q.y) / (r.y - q.y) + q.x) inside = !inside;
            }
            double distance = Math.sqrt(bestDistance);
            if (!inside && distance >= thickness) continue;
            Particle q = b.particles[nearestEdge], r = b.particles[(nearestEdge + 1) % OUTER];
            double nx, ny;
            if (distance > 1e-8) {
                nx = (p.x - nearestX) / distance; ny = (p.y - nearestY) / distance;
                if (inside) { nx = -nx; ny = -ny; }
            } else {
                double ex = r.x - q.x, ey = r.y - q.y, len = Math.max(1e-8, Math.hypot(ex, ey));
                nx = ey / len; ny = -ex / len;
            }
            solveParticleEdge(p, q, r, bestT, nx, ny, inside ? thickness + distance : thickness - distance);
        }
    }

    private static void solveParticleEdge(Particle p, Particle q, Particle r, double t, double nx, double ny, double depth) {
        double u = 1 - t;
        double invMass = p.invMass + u * u * q.invMass + t * t * r.invMass;
        double correction = Math.min(6, Math.max(0, depth - 0.02) * 0.65) / invMass;
        p.x += nx * correction * p.invMass; p.y += ny * correction * p.invMass;
        q.x -= nx * correction * q.invMass * u; q.y -= ny * correction * q.invMass * u;
        r.x -= nx * correction * r.invMass * t; r.y -= ny * correction * r.invMass * t;
        double rvx = p.vx - q.vx * u - r.vx * t, rvy = p.vy - q.vy * u - r.vy * t;
        double vn = rvx * nx + rvy * ny;
        if (vn >= 0) return;
        double impulse = -(1 + (vn < -90 ? RESTITUTION : 0)) * vn / invMass;
        double tangent = clamp(-(rvx * -ny + rvy * nx) / invMass, -FRICTION * impulse, FRICTION * impulse);
        double ix = nx * impulse - ny * tangent, iy = ny * impulse + nx * tangent;
        p.vx += ix * p.invMass; p.vy += iy * p.invMass;
        q.vx -= ix * q.invMass * u; q.vy -= iy * q.invMass * u;
        r.vx -= ix * r.invMass * t; r.vy -= iy * r.invMass * t;
    }

    private static void collideWalls(Particle p, double skin, double w, double h, double lv, double rv, double tv, double bv) {
        if (w <= skin * 2) { p.x = w / 2; p.vx = (lv + rv) / 2; }
        else {
            if (p.x < skin) { p.x = skin; wallImpulse(p, 1, 0, lv, tv); }
            if (p.x > w - skin) { p.x = w - skin; wallImpulse(p, -1, 0, rv, tv); }
        }
        if (h <= skin * 2) { p.y = h / 2; p.vy = (tv + bv) / 2; }
        else {
            if (p.y < skin) { p.y = skin; wallImpulse(p, 0, 1, lv, tv); }
            if (p.y > h - skin) { p.y = h - skin; wallImpulse(p, 0, -1, lv, bv); }
        }
    }

    private static void wallImpulse(Particle p, double nx, double ny, double vx, double vy) {
        double rvx = p.vx - vx, rvy = p.vy - vy, vn = rvx * nx + rvy * ny;
        if (vn >= 0) return;
        double normal = -(1 + (vn < -90 ? RESTITUTION : 0)) * vn;
        double tangent = clamp(-(rvx * -ny + rvy * nx), -FRICTION * normal, FRICTION * normal);
        p.vx += nx * normal - ny * tangent;
        p.vy += ny * normal + nx * tangent;
    }

    private static void limitVelocity(Particle p) {
        double speed = Math.hypot(p.vx, p.vy);
        if (speed > MAX_SPEED) { p.vx *= MAX_SPEED / speed; p.vy *= MAX_SPEED / speed; }
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private Color nextColor() { return Color.getHSBColor(random.nextFloat(), 0.60f, 0.96f); }
    private void message(String text) { status = text; statusTime = 2; }

    @Override
    public void render(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            Rectangle clip = g.getClipBounds();
            if (clip != null) g.fillRect(clip.x, clip.y, clip.width, clip.height);
            else g.fillRect(0, 0, (int) width, (int) height);
            for (SoftBody body : bodies) drawBody(g, body);
            if (grabbed != null) {
                g.setColor(new Color(255, 245, 180));
                g.setStroke(new BasicStroke(1.5f));
                g.drawLine((int) grabbed.x, (int) grabbed.y, (int) grabX, (int) grabY);
                g.draw(new Ellipse2D.Double(grabX - 5, grabY - 5, 10, 10));
            }
            g.setColor(new Color(5, 10, 20, 210));
            g.fillRoundRect(8, 8, 760, 134, 12, 12);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(225, 235, 250));
            g.drawString("SOFT BODIES | Left/Space: spawn | Right drag: pull a particle", 16, 27);
            g.drawString("B: skeleton + nodes | N: nodes | V: outline | C: clear | R: reset", 16, 46);
            g.drawString("1 Jelly | 2 Rubber | 3 Firm (all bodies) | Material: " + MATERIAL_NAMES[material], 16, 65);
            g.drawString("Bodies: " + bodies.size() + "/" + MAX_BODIES + " | Particles: " + bodies.size() * 25
                    + " | Skeleton: " + (skeleton ? "ON" : "OFF"), 16, 84);
            g.setColor(new Color(145, 255, 185));
            g.drawString(statsReady ? "Update FPS: %.1f | Max interval: %.1f ms".formatted(updateFps, shownInterval)
                    : "Update FPS: -- | collecting...", 16, 103);
            g.drawString("Update cost: avg %.2f ms | max %.2f ms".formatted(shownCost, shownMaxCost), 16, 122);
            if (skeleton) {
                g.setColor(new Color(220, 230, 245));
                g.drawString("Springs: red = stretched, blue = compressed, white = near rest", 16, 161);
            }
            if (statusTime > 0) { g.setColor(new Color(255, 220, 140)); g.drawString(status, 16, skeleton ? 181 : 161); }
        } finally { g.dispose(); }
    }

    private void drawBody(Graphics2D g, SoftBody body) {
        Path2D path = new Path2D.Double();
        path.moveTo(body.particles[0].x, body.particles[0].y);
        for (int i = 1; i < OUTER; i++) path.lineTo(body.particles[i].x, body.particles[i].y);
        path.closePath();
        // 直接画物理轮廓，不用样条掩盖节点实际位置。
        if (!outlinesOnly) {
            int alpha = skeleton ? 85 : 230;
            g.setColor(new Color(body.color.getRed(), body.color.getGreen(), body.color.getBlue(), alpha));
            g.fill(path);
        }
        g.setStroke(new BasicStroke(1.7f));
        g.setColor(body.color.brighter()); g.draw(path);
        if (skeleton) {
            g.setStroke(new BasicStroke(1));
            for (Spring spring : body.springs) {
                double strain = (Math.hypot(spring.b.x - spring.a.x, spring.b.y - spring.a.y) - spring.rest) / spring.rest;
                double level = Math.min(1, Math.abs(strain) * 4);
                Color target = strain >= 0 ? new Color(255, 100, 100) : new Color(80, 175, 255);
                int red = (int) (225 + (target.getRed() - 225) * level);
                int green = (int) (230 + (target.getGreen() - 230) * level);
                int blue = (int) (240 + (target.getBlue() - 240) * level);
                g.setColor(new Color(red, green, blue, spring.surface ? 230 : 155));
                g.drawLine((int) spring.a.x, (int) spring.a.y, (int) spring.b.x, (int) spring.b.y);
            }
        }
        if (skeleton || nodesOnly) {
            for (int i = 0; i < body.particles.length; i++) {
                Particle p = body.particles[i];
                double radius = i == CENTER ? 4.5 : i < OUTER ? 3 : 2.6;
                g.setColor(i == CENTER ? new Color(255, 220, 105) : i < OUTER ? Color.WHITE : new Color(160, 220, 255));
                g.fill(new Ellipse2D.Double(p.x - radius, p.y - radius, radius * 2, radius * 2));
                g.setColor(new Color(20, 30, 45));
                g.draw(new Ellipse2D.Double(p.x - radius, p.y - radius, radius * 2, radius * 2));
            }
        }
    }

    static void main() {
        runScene(new SoftBodyScene());
    }
}
