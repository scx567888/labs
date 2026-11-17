package dev.scx.physics;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;

public class TestScene implements Scene {

    private final ArrayList<Ball> balls;

    public TestScene() {
        this.balls = new ArrayList<>();
        balls.add(new Ball());
    }

    @Override
    public void update(SceneContext context) {
        var input = context.input();
        if (balls.isEmpty()) {
            return;
        }
        var ball = balls.get(0);
        if (input.keyDown(KeyEvent.VK_LEFT)) {
            ball.x -= 3;
        }
        if (input.keyDown(KeyEvent.VK_RIGHT)) {
            ball.x += 3;
        }
        if (input.keyDown(KeyEvent.VK_UP)) {
            ball.y -= 3;
        }
        if (input.keyDown(KeyEvent.VK_DOWN)) {
            ball.y += 3;
        }
    }

    @Override
    public void render(Graphics g) {
        for (var ball : balls) {

            // 画一个红色的小球
            g.setColor(ball.color);
            g.fillOval(ball.x, ball.y, 30, 30);
        }

    }

    static class Ball {
        int x;
        int y;
        Color color = Color.RED;
    }

}
