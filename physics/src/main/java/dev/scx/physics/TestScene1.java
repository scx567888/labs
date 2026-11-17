package dev.scx.physics;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Random;

import static dev.scx.physics.Helper.runScene;

/** 二维圆形刚体沙盒；所有物理状态和输入处理均封装在场景内。 */
public class TestScene1 implements Scene {

    private static final double GRAVITY = 1100; // px/s²
    private static final double RESTITUTION = 0.45;
    private static final double FRICTION = 0.32;
    private static final double MAX_SPEED = 4500;
    private static final int ITERATIONS = 7;
    private static final int MAX_BALLS = 400;
    private final ArrayList<Ball> balls = new ArrayList<>();
    private final Random random = new Random();
    private boolean wasMouseDown, wasSpaceDown;
    private long lastNanos;
    private double width, height, windowX, windowY, spawnCooldown;

    @Override
    public void start(SceneContext context) {
        int w = context.viewport().width(), h = context.viewport().height();

        width = w;
        height = h;
        windowX = context.window().x();
        windowY = context.window().y();
        seedBalls();
        // 初始化完成后开始计时，不把生成球体的耗时算进去。
        lastNanos = System.nanoTime();
    }

    @Override
    public void update(SceneContext context) {
        long now = System.nanoTime();

        double elapsed = Math.max(1.0 / 1000, (now - lastNanos) * 1e-9);
        lastNanos = now;
        advance(context, Math.min(elapsed, 0.05));
    }

    private void seedBalls() {
        // 不重叠的错列排列，沉降后约占窗口下半部。半径随窗口尺寸适配。
        double cell = Math.min(44, Math.min(width / 8, height / 6));
        if (cell < 4) return;
        int rows = Math.max(1, (int) (height * 0.65 / cell));
        int columns = Math.max(1, (int) (width / cell));
        for (int row = 0; row < rows && balls.size() < MAX_BALLS; row++) {
            for (int col = 0; col < columns && balls.size() < MAX_BALLS; col++) {
                double radius = cell * (0.35 + random.nextDouble() * 0.14);
                double x = (col + 0.5) * cell + (row % 2) * cell * 0.15;
                if (x + radius > width) continue;
                balls.add(new Ball(x, height - (row + 0.5) * cell, radius, nextColor()));
            }
        }
    }

    /** 坐标是窗口内坐标，速度却是桌面坐标系速度：移动窗口不会直接拖着球走。 */
    private void advance(SceneContext context, double dt) {
        double newWidth = context.viewport().width(), newHeight = context.viewport().height();
        double dx = context.window().x() - windowX, dy = context.window().y() - windowY;
        double dw = newWidth - width, dh = newHeight - height;
        // 四面墙各自的桌面速度，同时处理左/上边框缩放与普通窗口移动。
        double leftV = dx / dt, topV = dy / dt;
        double rightV = (dx + dw) / dt, bottomV = (dy + dh) / dt;
        double minRadius = 20, speed = 0;
        for (Ball b : balls) {
            minRadius = Math.min(minRadius, b.radius);
            speed = Math.max(speed, Math.hypot(b.vx, b.vy));
        }
        double wallTravel = Math.max(Math.max(Math.abs(dx), Math.abs(dy)),
                Math.max(Math.abs(dx + dw), Math.abs(dy + dh)));
        // 高频子步防止高速球/移动墙穿透；限制极端系统跳变的计算量。
        int steps = Math.min(256, Math.max((int) Math.ceil(dt * 240),
                (int) Math.ceil((speed * dt + wallTravel) / Math.max(1, minRadius * 0.45))));
        double step = dt / steps;
        double oldWidth = width, oldHeight = height;
        for (int s = 1; s <= steps; s++) {
            double w = oldWidth + dw * s / steps, h = oldHeight + dh * s / steps;
            for (Ball b : balls) {
                b.vy += GRAVITY * step;
                limitSpeed(b);
                b.x += b.vx * step - dx / steps;
                b.y += b.vy * step - dy / steps;
                b.angle += b.spin * step;
            }
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                for (int i = 0; i < balls.size(); i++) {
                    for (int j = i + 1; j < balls.size(); j++) collide(balls.get(i), balls.get(j));
                }
                for (Ball b : balls) walls(b, w, h, leftV, rightV, topV, bottomV);
            }
        }
        width = newWidth;
        height = newHeight;
        windowX = context.window().x();
        windowY = context.window().y();
        handleInput(context.input(), dt, leftV, topV);
    }

    private void handleInput(Input input, double dt, double vx, double vy) {
        boolean mouse = input.mouseDown(MouseEvent.BUTTON1);
        boolean space = input.keyDown(KeyEvent.VK_SPACE);
        spawnCooldown -= dt;
        if ((mouse && !wasMouseDown) || (space && !wasSpaceDown)
                || ((mouse || space) && spawnCooldown <= 0)) {
            double x = mouse ? input.mouseX() : width / 2;
            double y = mouse ? input.mouseY() : 35;
            spawn(x, y, vx, vy);
            spawnCooldown = 0.12;
        }
        wasMouseDown = mouse;
        wasSpaceDown = space;
    }

    private void spawn(double x, double y, double vx, double vy) {
        if (balls.size() >= MAX_BALLS || width < 8 || height < 8) return;
        double r = Math.min(10 + random.nextDouble() * 16, Math.min(width, height) / 2 - 1);
        // 新增球只放在空位，避免重叠生成向密集球堆注入巨大能量。
        for (int attempt = 0; attempt < 100; attempt++) {
            double px = clamp(attempt == 0 ? x : r + random.nextDouble() * (width - 2 * r), r, width - r);
            double py = clamp(attempt == 0 ? y : r + random.nextDouble() * (height - 2 * r), r, height - r);
            boolean free = true;
            for (Ball b : balls) {
                if (Math.hypot(px - b.x, py - b.y) < r + b.radius + 1) {
                    free = false;
                    break;
                }
            }
            if (free) {
                Ball b = new Ball(px, py, r, nextColor());
                b.vx = vx;
                b.vy = vy;
                limitSpeed(b);
                balls.add(b);
                return;
            }
        }
    }

    private static void collide(Ball a, Ball b) {
        double dx = b.x - a.x, dy = b.y - a.y, radius = a.radius + b.radius;
        double distanceSquared = dx * dx + dy * dy;
        if (distanceSquared >= radius * radius) return;
        double distance = Math.sqrt(distanceSquared);
        double nx = distance > 1e-8 ? dx / distance : 1;
        double ny = distance > 1e-8 ? dy / distance : 0;
        double inverseMass = a.inverseMass + b.inverseMass;
        double correction = Math.max(0, radius - distance - 0.01) * 0.8 / inverseMass;
        a.x -= correction * a.inverseMass * nx;
        a.y -= correction * a.inverseMass * ny;
        b.x += correction * b.inverseMass * nx;
        b.y += correction * b.inverseMass * ny;
        double vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
        if (vn >= 0) return;
        // 低速接触不反弹，便于稳定堆叠；高速碰撞使用恢复系数。
        double impulse = -(1 + (vn < -45 ? RESTITUTION : 0)) * vn / inverseMass;
        double tangentSpeed = (b.vx - a.vx) * -ny + (b.vy - a.vy) * nx
                - a.spin * a.radius - b.spin * b.radius;
        double friction = clamp(-tangentSpeed / (3 * inverseMass), -FRICTION * impulse, FRICTION * impulse);
        double ix = impulse * nx - friction * ny, iy = impulse * ny + friction * nx;
        a.vx -= ix * a.inverseMass;
        a.vy -= iy * a.inverseMass;
        b.vx += ix * b.inverseMass;
        b.vy += iy * b.inverseMass;
        a.spin -= friction * a.radius * a.inverseInertia;
        b.spin -= friction * b.radius * b.inverseInertia;
    }

    private static void walls(Ball b, double w, double h, double lv, double rv, double tv, double bv) {
        // 容器比直径还小时保留球尺寸，将该轴约束在中心；恢复尺寸后自然释放。
        if (w <= 2 * b.radius) {
            b.x = w / 2;
            b.vx = (lv + rv) / 2;
        } else {
            if (b.x <= b.radius) { b.x = b.radius; wallImpulse(b, 1, 0, lv, tv); }
            if (b.x >= w - b.radius) { b.x = w - b.radius; wallImpulse(b, -1, 0, rv, tv); }
        }
        if (h <= 2 * b.radius) {
            b.y = h / 2;
            b.vy = (tv + bv) / 2;
        } else {
            if (b.y <= b.radius) { b.y = b.radius; wallImpulse(b, 0, 1, lv, tv); }
            if (b.y >= h - b.radius) { b.y = h - b.radius; wallImpulse(b, 0, -1, lv, bv); }
        }
        limitSpeed(b);
    }

    private static void wallImpulse(Ball b, double nx, double ny, double wallVX, double wallVY) {
        double vn = (b.vx - wallVX) * nx + (b.vy - wallVY) * ny;
        if (vn >= 0) return;
        double impulse = -(1 + (vn < -45 ? RESTITUTION : 0)) * vn / b.inverseMass;
        double tangentSpeed = (b.vx - wallVX) * -ny + (b.vy - wallVY) * nx - b.spin * b.radius;
        double friction = clamp(-tangentSpeed / (3 * b.inverseMass), -FRICTION * impulse, FRICTION * impulse);
        b.vx += (impulse * nx - friction * ny) * b.inverseMass;
        b.vy += (impulse * ny + friction * nx) * b.inverseMass;
        b.spin -= friction * b.radius * b.inverseInertia;
    }

    private static void limitSpeed(Ball b) {
        double speed = Math.hypot(b.vx, b.vy);
        if (speed > MAX_SPEED) { b.vx *= MAX_SPEED / speed; b.vy *= MAX_SPEED / speed; }
        b.spin = clamp(b.spin, -150, 150);
    }

    private Color nextColor() {
        return Color.getHSBColor(random.nextFloat(), 0.55f + random.nextFloat() * 0.3f, 0.95f);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void render(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(24, 29, 39));
            g.fillRect(0, 0, (int) width, (int) height);
            for (Ball b : balls) {
                double diameter = 2 * b.radius;
                Ellipse2D shape = new Ellipse2D.Double(b.x - b.radius, b.y - b.radius, diameter, diameter);
                g.setColor(b.color);
                g.fill(shape);
                g.setColor(b.color.darker());
                g.draw(shape);
                // 标记同时显示碰撞摩擦带来的自转。
                g.setColor(new Color(255, 255, 255, 150));
                double mx = b.x + Math.cos(b.angle) * b.radius * 0.5;
                double my = b.y + Math.sin(b.angle) * b.radius * 0.5;
                double mark = Math.max(2, b.radius * 0.2);
                g.fill(new Ellipse2D.Double(mx - mark / 2, my - mark / 2, mark, mark));
            }
            g.setColor(new Color(230, 235, 245));
            g.drawString("Left click / hold: add balls   |   Space: add balls   |   Move / resize / shake window", 12, 22);
            g.drawString("Balls: " + balls.size() + " / " + MAX_BALLS, 12, 42);
        } finally {
            g.dispose();
        }
    }

    private static class Ball {
        double x, y, vx, vy, angle, spin;
        final double radius, inverseMass, inverseInertia;
        final Color color;

        Ball(double x, double y, double radius, Color color) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.color = color;
            inverseMass = 1 / (radius * radius); // 同密度圆盘，质量与面积成正比
            inverseInertia = 2 * inverseMass / (radius * radius);
        }
    }


    static void main() {
        runScene(new TestScene1());
    }
}
