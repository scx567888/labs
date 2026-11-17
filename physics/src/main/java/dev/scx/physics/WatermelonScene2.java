package dev.scx.physics;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Random;

import static dev.scx.physics.Helper.runScene;

/**
 * 数字版合成大西瓜，直接放入 TestScene1 所在包即可。
 * 与 TestScene1 一样：位置使用窗口坐标，速度使用桌面坐标。
 * 移动窗口时球保持惯性，移动/缩放产生的四面墙速度参与碰撞冲量。
 * 鼠标瞄准、左键/空格投放，R 重开；摇晃窗口可以帮助合成。
 */
public class WatermelonScene2 implements Scene {
    private static final double GRAVITY = 1100, RESTITUTION = .45, FRICTION = .32;
    private static final double MAX_SPEED = 4500;
    private static final int ITERATIONS = 7, MAX_BALLS = 400;
    // 下标即等级；显示值为 2^(等级+1)，最后一级 2048 是“大西瓜”。
    private static final double[] RADII = {14, 18, 23, 29, 36, 44, 53, 63, 74, 86, 100};
    private static final Color[] COLORS = {
        new Color(255,189,162), new Color(255,211,130), new Color(248,222,100),
        new Color(203,223,120), new Color(143,211,151), new Color(108,204,190),
        new Color(125,196,228), new Color(154,174,229), new Color(187,161,226),
        new Color(229,156,192), new Color(94,195,123)
    };
    /** 修改这里即可调整解锁门槛和各等级的抽取权重。索引 0 表示数字 2。 */
    private static final SpawnPool[] POOLS = {
        new SpawnPool("萌芽池", 0, new int[]{70, 30}),
        new SpawnPool("成长池", 100, new int[]{45, 35, 20}),
        new SpawnPool("丰收池", 500, new int[]{25, 35, 25, 15}),
        new SpawnPool("西瓜池", 1500, new int[]{20, 25, 25, 20, 10})
    };

    private final ArrayList<Ball> balls = new ArrayList<>();
    private final ArrayList<Pop> pops = new ArrayList<>();
    private final Random random = new Random();
    private long score, best, lastNanos;
    private int poolIndex, current, next;
    private double width, height, windowX, windowY, aimX, cooldown, danger;
    private boolean mouseWasDown, spaceWasDown, restartWasDown, gameOver, won;
    private String notice;
    private double noticeTime;

    @Override public void start(SceneContext context) {
        width = context.viewport().width(); height = context.viewport().height();
        windowX = context.window().x(); windowY = context.window().y();
        aimX = width / 2;
        reset(); lastNanos = System.nanoTime();
    }

    private void reset() {
        balls.clear(); pops.clear(); score = 0; poolIndex = 0;
        cooldown = danger = 0; gameOver = won = false;
        current = drawLevel(); next = drawLevel();
        notice = "移动 / 晃动 / 缩放窗口，球会受到墙体推动"; noticeTime = 5;
    }

    @Override public void update(SceneContext context) {
        long now = System.nanoTime();
        double dt = Math.min(.05, Math.max(.001, (now - lastNanos) * 1e-9));
        lastNanos = now;
        advance(context, dt);
    }

    /** 包可见以便测试；dt 必须大于零。 */
    void advance(SceneContext context, double dt) {
        if (dt <= 0) return;
        Input input = context.input();
        boolean restart = input.keyDown(KeyEvent.VK_R);
        if (restart && !restartWasDown) reset();
        restartWasDown = restart;

        double newWidth = context.viewport().width(), newHeight = context.viewport().height();
        double dx = context.window().x() - windowX, dy = context.window().y() - windowY;
        double dw = newWidth - width, dh = newHeight - height;
        // 左/上边框移动与右/下边框缩放分别产生不同桌面速度。
        double leftV = dx / dt, topV = dy / dt;
        double rightV = (dx + dw) / dt, bottomV = (dy + dh) / dt;
        if (!gameOver) {
            double minRadius = RADII[0], speed = 0;
            for (Ball b : balls) speed = Math.max(speed, Math.hypot(b.vx, b.vy));
            double travel = Math.max(Math.max(Math.abs(dx), Math.abs(dy)),
                    Math.max(Math.abs(dx + dw), Math.abs(dy + dh)));
            // 沿用 TestScene1 的自适应子步：球越快、窗口移动越远，子步越多。
            int steps = Math.min(256, Math.max((int) Math.ceil(dt * 240),
                    (int) Math.ceil((speed * dt + travel) / Math.max(1, minRadius * .45))));
            double step = dt / steps;
            for (int s = 1; s <= steps; s++) {
                double w = width + dw * s / steps, h = height + dh * s / steps;
                for (Ball b : balls) {
                    b.age += step; b.vy += GRAVITY * step; limitSpeed(b);
                    // 关键：从窗口相对位置扣掉窗口位移，保留球在桌面上的惯性。
                    b.x += b.vx * step - dx / steps;
                    b.y += b.vy * step - dy / steps;
                }
                mergeOnePair();
                for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                    for (int i = 0; i < balls.size(); i++) {
                        for (int j = i + 1; j < balls.size(); j++) collide(balls.get(i), balls.get(j));
                    }
                    for (Ball b : balls) walls(b, w, h, leftV, rightV, topV, bottomV);
                }
            }
            // 浮字也保持在其原有桌面位置附近。
            for (Pop pop : pops) { pop.x -= dx; pop.y -= dy + 26 * dt; pop.life -= dt; }
            pops.removeIf(pop -> pop.life <= 0);
            noticeTime = Math.max(0, noticeTime - dt);
        }
        width = newWidth; height = newHeight;
        windowX = context.window().x(); windowY = context.window().y();
        if (!gameOver) {
            boolean overLine = false;
            for (Ball b : balls) {
                if (b.age > 1.2 && b.y - b.radius < limitY()) { overLine = true; break; }
            }
            danger = overLine ? danger + dt : 0;
            if (danger >= 2.5) gameOver = true;
        }
        boolean mouse = input.mouseDown(MouseEvent.BUTTON1);
        boolean space = input.keyDown(KeyEvent.VK_SPACE);
        if (input.mouseX() >= 0) aimX = input.mouseX();
        aimX = width <= 2 * RADII[current] ? width / 2
                : clamp(aimX, RADII[current], width - RADII[current]);
        cooldown = Math.max(0, cooldown - dt);
        if (!gameOver && !restart && cooldown == 0
                && ((mouse && !mouseWasDown) || (space && !spaceWasDown))) {
            drop(leftV, topV);
        }
        mouseWasDown = mouse; spaceWasDown = space;
    }

    private double dropY() { return Math.min(100, height * .18); }
    private double limitY() { return Math.min(150, height * .28); }
    private int drawLevel() { return POOLS[poolIndex].draw(random); }

    private void drop(double windowVX, double windowVY) {
        double r = RADII[current];
        if (balls.size() >= MAX_BALLS || width < 2 * r || height < 2 * r) return;
        double y = clamp(dropY(), r, height - r);
        for (Ball b : balls) {
            if (Math.hypot(aimX - b.x, y - b.y) < r + b.radius + 1) {
                notice = "落点被挡住了，换个位置试试"; noticeTime = 1.2; return;
            }
        }
        Ball b = new Ball(aimX, y, current);
        // 与 TestScene1 相同：新生成球继承投放瞬间的窗口平移速度。
        b.vx = windowVX; b.vy = windowVY; limitSpeed(b); balls.add(b);
        current = next; next = drawLevel(); cooldown = .38;
    }

    private void mergeOnePair() {
        Ball first = null, second = null;
        outer: for (int i = 0; i < balls.size(); i++) {
            Ball a = balls.get(i);
            for (int j = i + 1; j < balls.size(); j++) {
                Ball b = balls.get(j);
                if (a.level == b.level && a.level < RADII.length - 1
                        && a.age >= .06 && b.age >= .06
                        && Math.hypot(a.x - b.x, a.y - b.y) <= a.radius + b.radius + .15) {
                    first = a; second = b; break outer;
                }
            }
        }
        if (first != null) merge(first, second);
    }

    private void merge(Ball a, Ball b) {
        Ball merged = new Ball((a.x + b.x) / 2, (a.y + b.y) / 2, a.level + 1);
        merged.vx = (a.vx + b.vx) / 2; merged.vy = (a.vy + b.vy) / 2;
        merged.spin = (a.spin + b.spin) / 2;
        balls.remove(a); balls.remove(b); balls.add(merged);
        int points = value(merged.level);
        score += points; best = Math.max(best, score);
        pops.add(new Pop(merged.x, merged.y, "+" + points));
        while (poolIndex + 1 < POOLS.length && score >= POOLS[poolIndex + 1].unlockScore) {
            poolIndex++;
            notice = "已解锁 " + POOLS[poolIndex].name + "！"; noticeTime = 3;
        }
        if (merged.level == RADII.length - 1 && !won) {
            won = true; notice = "合成大西瓜！继续挑战更高分"; noticeTime = 5;
        }
    }

    private static int value(int level) { return 1 << (level + 1); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    // 与 TestScene1 相同的逆质量位置修正、法向冲量和圆盘摩擦。
    private static void collide(Ball a, Ball b) {
        double dx = b.x - a.x, dy = b.y - a.y, radius = a.radius + b.radius;
        double d2 = dx * dx + dy * dy;
        if (d2 >= radius * radius) return;
        double distance = Math.sqrt(d2);
        double nx = distance > 1e-8 ? dx / distance : 1;
        double ny = distance > 1e-8 ? dy / distance : 0;
        double inverseMass = a.inverseMass + b.inverseMass;
        double correction = Math.max(0, radius - distance - .01) * .8 / inverseMass;
        a.x -= correction * a.inverseMass * nx; a.y -= correction * a.inverseMass * ny;
        b.x += correction * b.inverseMass * nx; b.y += correction * b.inverseMass * ny;
        double vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
        if (vn >= 0) return;
        double impulse = -(1 + (vn < -45 ? RESTITUTION : 0)) * vn / inverseMass;
        double tangentSpeed = (b.vx - a.vx) * -ny + (b.vy - a.vy) * nx
                - a.spin * a.radius - b.spin * b.radius;
        double friction = clamp(-tangentSpeed / (3 * inverseMass), -FRICTION * impulse, FRICTION * impulse);
        double ix = impulse * nx - friction * ny, iy = impulse * ny + friction * nx;
        a.vx -= ix * a.inverseMass; a.vy -= iy * a.inverseMass;
        b.vx += ix * b.inverseMass; b.vy += iy * b.inverseMass;
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


    @Override public void render(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(247,247,234)); g.fillRect(0, 0, (int) width, (int) height);
            g.setColor(danger > 0 ? new Color(215,93,85) : new Color(203,187,159));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, 0));
            g.drawLine(0, (int) limitY(), (int) width, (int) limitY());
            if (!gameOver && width >= 2 * RADII[current] && height >= 2 * RADII[current]) {
                double x = clamp(aimX, RADII[current], width - RADII[current]);
                double y = clamp(dropY(), RADII[current], height - RADII[current]);
                g.setColor(new Color(195,209,180));
                g.drawLine((int) x, (int) y, (int) x, (int) height);
                drawBall(g, x, y, current, RADII[current], cooldown > 0 ? .3f : .65f);
            }
            for (Ball b : balls) drawBall(g, b.x, b.y, b.level, b.radius, 1);
            for (Pop pop : pops) text(g, pop.text, (int) pop.x - 12, (int) pop.y, 18, new Color(44,126,73));
            // HUD 只叠加显示，不是固定逻辑画布；四面物理墙就是实际视口边缘。
            g.setColor(new Color(247,247,234,215)); g.fillRect(0, 0, (int) width, 72);
            text(g, "合成大西瓜", 12, 25, 20, new Color(38,72,51));
            text(g, POOLS[poolIndex].name + " · 下一个 " + value(next), 12, 47, 13, new Color(68,109,76));
            String poolHint = poolIndex + 1 < POOLS.length
                    ? POOLS[poolIndex + 1].unlockScore + " 分解锁" + POOLS[poolIndex + 1].name : "全部池已解锁";
            text(g, poolHint, 12, 65, 12, new Color(105,127,110));
            String scoreText = "得分 " + score;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            int scoreX = Math.max(12, (int) width - g.getFontMetrics().stringWidth(scoreText) - 14);
            text(g, scoreText, scoreX, 29, 24, new Color(38,72,51));
            String bestText = "本次最佳 " + best;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            text(g, bestText, Math.max(12, (int) width - g.getFontMetrics().stringWidth(bestText) - 14), 49, 12, new Color(105,127,110));
            if (danger > 0 && !gameOver) text(g, String.format("超线 %.1f 秒后结束", Math.max(0, 2.5 - danger)), 12,
                    (int) limitY() - 8, 13, new Color(190,75,65));
            text(g, noticeTime > 0 ? notice : "左键 / 空格投放  |  R 重开  |  晃动窗口帮助合成", 12,
                    (int) height - 12, 12, new Color(68,109,76));
            if (gameOver) {
                int w = Math.min(360, (int) width), h = Math.min(142, (int) height);
                int x = ((int) width - w) / 2, y = ((int) height - h) / 2;
                g.setColor(new Color(247,247,234,235)); g.fillRoundRect(x, y, w, h, 22, 22);
                text(g, "本局结束 · " + score + " 分", x + 24, y + 55, 24, new Color(38,72,51));
                text(g, "按 R 重新开始", x + 24, y + 100, 18, new Color(68,109,76));
            }
        } finally { g.dispose(); }
    }

    private static void text(Graphics2D g, String text, int x, int y, int size, Color color) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size)); g.setColor(color); g.drawString(text, x, y);
    }
    private static void drawBall(Graphics2D g, double x, double y, int level, double r, float alpha) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.setColor(COLORS[level]);
        Ellipse2D circle = new Ellipse2D.Double(x - r, y - r, r * 2, r * 2);
        g.fill(circle); g.setColor(COLORS[level].darker()); g.setStroke(new BasicStroke(2)); g.draw(circle);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.max(12, r * .59)));
        String label = Integer.toString(value(level)); FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(45,62,49));
        g.drawString(label, (float) (x - fm.stringWidth(label) / 2.0), (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0));
        g.setComposite(old);
    }

    private static class Ball {
        double x, y, vx, vy, spin, age;
        final int level;
        final double radius, inverseMass, inverseInertia;
        Ball(double x, double y, int level) {
            this.x = x; this.y = y; this.level = level;
            radius = RADII[level]; inverseMass = 1 / (radius * radius);
            inverseInertia = 2 * inverseMass / (radius * radius);
        }
    }
    private static class Pop {
        double x; double y, life = .8; final String text;
        Pop(double x, double y, String text) { this.x = x; this.y = y; this.text = text; }
    }
    private record SpawnPool(String name, long unlockScore, int[] weights) {
        int draw(Random random) {
            int total = 0; for (int w : weights) total += w;
            int ticket = random.nextInt(total);
            for (int level = 0; level < weights.length; level++) {
                ticket -= weights[level]; if (ticket < 0) return level;
            }
            throw new IllegalStateException("生成池权重无效");
        }
    }


    public static void main(String[] args) {
        runScene(new WatermelonScene2());
    }
}
