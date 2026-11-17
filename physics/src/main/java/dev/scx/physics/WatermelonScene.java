package dev.scx.physics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Random;

/** 数字版合成大西瓜。逻辑坐标固定，窗口缩放不改变游戏难度。 */
public class WatermelonScene implements Scene {
    private static final int WIDTH = 800, HEIGHT = 760;
    private static final double LEFT = 46, RIGHT = 550, FLOOR = 694;
    private static final double DROP_Y = 157, LIMIT_Y = 218;
    private static final double GRAVITY = 1100, RESTITUTION = .18, FRICTION = .32;
    private static final double MAX_SPEED = 1800, FIXED_DT = 1.0 / 240;
    private static final int ITERATIONS = 7;
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
    private double aimX = (LEFT + RIGHT) / 2, cooldown, danger, accumulator;
    private double scale = 1, offsetX, offsetY;
    private int viewportW = WIDTH, viewportH = HEIGHT;
    private boolean mouseWasDown, spaceWasDown, restartWasDown, pauseWasDown;
    private boolean gameOver, paused, won;
    private String notice = "相同数字相碰，即可合成";
    private double noticeTime = 4;

    @Override public void start(SceneContext context) {
        fit(context.viewport());
        reset();
        lastNanos = System.nanoTime();
    }

    private void reset() {
        balls.clear(); pops.clear(); score = 0; poolIndex = 0;
        cooldown = danger = accumulator = 0;
        gameOver = paused = won = false;
        current = drawLevel(); next = drawLevel();
        notice = "相同数字相碰，即可合成"; noticeTime = 4;
    }

    private void fit(Viewport viewport) {
        viewportW = Math.max(1, viewport.width()); viewportH = Math.max(1, viewport.height());
        scale = Math.min(viewportW / (double) WIDTH, viewportH / (double) HEIGHT);
        offsetX = (viewportW - WIDTH * scale) / 2;
        offsetY = (viewportH - HEIGHT * scale) / 2;
    }

    @Override public void update(SceneContext context) {
        long now = System.nanoTime();
        double dt = Math.min(.05, Math.max(0, (now - lastNanos) * 1e-9));
        lastNanos = now;
        advance(context, dt);
    }

    // 包可见，便于无窗口测试使用确定的时间步。
    void advance(SceneContext context, double dt) {
        fit(context.viewport());
        Input input = context.input();
        boolean mouse = input.mouseDown(MouseEvent.BUTTON1);
        boolean space = input.keyDown(KeyEvent.VK_SPACE);
        boolean restart = input.keyDown(KeyEvent.VK_R);
        boolean pause = input.keyDown(KeyEvent.VK_P);
        boolean resetThisFrame = restart && !restartWasDown;
        if (resetThisFrame) reset();
        if (pause && !pauseWasDown && !gameOver && !resetThisFrame) paused = !paused;
        double mx = (input.mouseX() - offsetX) / scale;
        double my = (input.mouseY() - offsetY) / scale;
        if (input.mouseX() >= 0) aimX = mx;
        aimX = clamp(aimX, LEFT + RADII[current], RIGHT - RADII[current]);
        if (!paused && !gameOver && !resetThisFrame) {
            cooldown = Math.max(0, cooldown - dt);
            boolean clickInField = mouse && !mouseWasDown && mx >= LEFT && mx <= RIGHT
                    && my >= 110 && my <= FLOOR;
            if (cooldown == 0 && (clickInField || (space && !spaceWasDown))) drop();
            accumulator += dt;
            while (accumulator >= FIXED_DT && !gameOver) {
                simulate(FIXED_DT);
                accumulator -= FIXED_DT;
            }
            noticeTime = Math.max(0, noticeTime - dt);
            for (Pop pop : pops) { pop.life -= dt; pop.y -= 26 * dt; }
            pops.removeIf(pop -> pop.life <= 0);
        }
        mouseWasDown = mouse; spaceWasDown = space;
        restartWasDown = restart; pauseWasDown = pause;
    }

    private int drawLevel() { return POOLS[poolIndex].draw(random); }

    private void drop() {
        double r = RADII[current];
        for (Ball b : balls) {
            if (Math.hypot(aimX - b.x, DROP_Y - b.y) < r + b.radius + 1) {
                notice = "落点被挡住了，换个位置试试"; noticeTime = 1.2;
                return;
            }
        }
        balls.add(new Ball(aimX, DROP_Y, current));
        current = next; next = drawLevel(); cooldown = .38;
    }

    private void simulate(double dt) {
        for (Ball b : balls) {
            b.age += dt; b.vy += GRAVITY * dt; limitSpeed(b);
            b.x += b.vx * dt; b.y += b.vy * dt;
        }
        // 每次扫描至多合成一对，先退出遍历再修改列表；同一球不会重复计分。
        // 新球延迟 0.06 秒参与合成，避免同一子步连锁造成尺寸突变。
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
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (int i = 0; i < balls.size(); i++) {
                for (int j = i + 1; j < balls.size(); j++) collide(balls.get(i), balls.get(j));
            }
            for (Ball b : balls) {
                if (b.x < LEFT + b.radius) { b.x = LEFT + b.radius; wallImpulse(b, 1, 0); }
                if (b.x > RIGHT - b.radius) { b.x = RIGHT - b.radius; wallImpulse(b, -1, 0); }
                if (b.y > FLOOR - b.radius) { b.y = FLOOR - b.radius; wallImpulse(b, 0, -1); }
                limitSpeed(b);
            }
        }
        boolean overLine = false;
        for (Ball b : balls) {
            // 新落下的球与刚合成的球有宽限期，不因途经警戒线立即失败。
            if (b.age > 1.2 && b.y - b.radius < LIMIT_Y) { overLine = true; break; }
        }
        danger = overLine ? danger + dt : 0;
        if (danger >= 2.5) { gameOver = true; best = Math.max(best, score); }
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

    private static void wallImpulse(Ball b, double nx, double ny) {
        double vn = b.vx * nx + b.vy * ny;
        if (vn >= 0) return;
        double impulse = -(1 + (vn < -45 ? RESTITUTION : 0)) * vn / b.inverseMass;
        double tangent = b.vx * -ny + b.vy * nx - b.spin * b.radius;
        double friction = clamp(-tangent / (3 * b.inverseMass), -FRICTION * impulse, FRICTION * impulse);
        b.vx += (impulse * nx - friction * ny) * b.inverseMass;
        b.vy += (impulse * ny + friction * nx) * b.inverseMass;
        b.spin -= friction * b.radius * b.inverseInertia;
    }

    private static void limitSpeed(Ball b) {
        double speed = Math.hypot(b.vx, b.vy);
        if (speed > MAX_SPEED) { b.vx *= MAX_SPEED / speed; b.vy *= MAX_SPEED / speed; }
        b.spin = clamp(b.spin, -100, 100);
    }

    @Override public void render(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(new Color(231,235,221)); g.fillRect(0, 0, viewportW, viewportH);
            g.translate(offsetX, offsetY); g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(247,247,234)); g.fillRect(0, 0, WIDTH, HEIGHT);
            text(g, "合成大西瓜", 40, 54, 30, new Color(38,72,51));
            text(g, "数字碰撞 · 慢慢长大", 42, 82, 14, new Color(112,130,113));
            panel(g, 578, 26, 184, 114);
            text(g, "得分", 598, 52, 14, new Color(105,127,110));
            text(g, Long.toString(score), 598, 91, 32, new Color(38,72,51));
            text(g, "本次运行最佳  " + best, 598, 120, 12, new Color(105,127,110));
            g.setColor(Color.WHITE); g.fillRoundRect(38, 110, 520, 594, 26, 26);
            g.setColor(new Color(221,230,207)); g.setStroke(new BasicStroke(5));
            g.drawLine((int) LEFT - 3, 244, (int) LEFT - 3, (int) FLOOR + 3);
            g.drawLine((int) LEFT - 3, (int) FLOOR + 3, (int) RIGHT + 3, (int) FLOOR + 3);
            g.drawLine((int) RIGHT + 3, 244, (int) RIGHT + 3, (int) FLOOR + 3);
            g.setColor(danger > 0 ? new Color(215,93,85) : new Color(203,187,159));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, 0));
            g.drawLine((int) LEFT, (int) LIMIT_Y, (int) RIGHT, (int) LIMIT_Y);
            text(g, danger > 0 ? String.format("超线 %.1f 秒后结束", Math.max(0, 2.5 - danger)) : "警戒线", 62, 208, 12, new Color(185,128,100));
            if (!gameOver) {
                double x = clamp(aimX, LEFT + RADII[current], RIGHT - RADII[current]);
                double landing = FLOOR - RADII[current];
                for (Ball b : balls) {
                    double dx = x - b.x, sum = RADII[current] + b.radius;
                    if (Math.abs(dx) < sum) landing = Math.min(landing, b.y - Math.sqrt(sum * sum - dx * dx));
                }
                landing = Math.max(DROP_Y, landing);
                g.setColor(new Color(195,209,180)); g.drawLine((int) x, (int) DROP_Y, (int) x, (int) landing);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Ellipse2D.Double(x - RADII[current], landing - RADII[current], 2 * RADII[current], 2 * RADII[current]));
                drawBall(g, x, DROP_Y, current, RADII[current], cooldown > 0 ? .5f : 1f);
            }
            for (Ball b : balls) drawBall(g, b.x, b.y, b.level, b.radius, 1);
            for (Pop pop : pops) text(g, pop.text, (int) pop.x - 12, (int) pop.y, 18, new Color(44,126,73));
            panel(g, 578, 154, 184, 134);
            text(g, "下一个", 598, 182, 14, new Color(105,127,110));
            drawBall(g, 670, 235, next, Math.min(32, RADII[next]), 1);
            panel(g, 578, 304, 184, 270);
            text(g, POOLS[poolIndex].name, 598, 334, 20, new Color(38,72,51));
            text(g, "当前生成概率", 598, 360, 12, new Color(105,127,110));
            int[] weights = POOLS[poolIndex].weights;
            int total = 0; for (int weight : weights) total += weight;
            for (int i = 0; i < weights.length; i++) {
                int y = 389 + 27 * i;
                text(g, Integer.toString(value(i)), 600, y, 14, new Color(38,72,51));
                g.setColor(COLORS[i]); g.fillRoundRect(634, y - 10, weights[i] * 70 / total, 9, 6, 6);
                text(g, weights[i] * 100 / total + "%", 714, y, 12, new Color(105,127,110));
            }
            if (poolIndex + 1 < POOLS.length) {
                SpawnPool upcoming = POOLS[poolIndex + 1];
                text(g, upcoming.unlockScore + " 分解锁" + upcoming.name, 598, 550, 12, new Color(105,127,110));
            } else text(g, "所有生成池已解锁", 598, 550, 12, new Color(105,127,110));
            text(g, "目标 2048", 596, 613, 21, new Color(38,72,51));
            text(g, "移动鼠标瞄准", 596, 646, 13, new Color(105,127,110));
            text(g, "点击 / 空格投放", 596, 669, 13, new Color(105,127,110));
            text(g, "P 暂停   R 重新开始", 596, 692, 13, new Color(105,127,110));
            text(g, noticeTime > 0 ? notice : "2 → 4 → 8 → … → 2048", 42, 738, 15, new Color(68,109,76));
            if (paused || gameOver) {
                g.setColor(new Color(247,247,234,225)); g.fillRoundRect(82, 320, 432, 182, 28, 28);
                text(g, gameOver ? "本局结束" : "已暂停", 214, 376, 30, new Color(38,72,51));
                text(g, gameOver ? "最终得分  " + score : "休息一下，再继续合成", 196, 417, 18, new Color(68,109,76));
                text(g, gameOver ? "按 R 重新开始" : "按 P 继续", 234, 462, 16, new Color(105,127,110));
            }
        } finally { g.dispose(); }
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(235,240,222)); g.fillRoundRect(x, y, w, h, 22, 22);
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
        final double x; double y, life = .8; final String text;
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
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("合成大西瓜 · 数字版");
            SceneRunner runner = new SceneRunner(new WatermelonScene());
            runner.setPreferredSize(new Dimension(WIDTH, HEIGHT));
            frame.setContentPane(runner); frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.pack(); frame.setMinimumSize(new Dimension(640, 640));
            frame.setLocationRelativeTo(null); frame.setVisible(true); runner.requestFocusInWindow();
        });
    }
}
