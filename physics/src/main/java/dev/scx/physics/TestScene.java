package dev.scx.physics;

import java.awt.*;
import java.awt.event.KeyEvent;

public class TestScene implements Scene {

    private int ballX = 0;
    private int ballY = 0;

    @Override
    public void update(Input input) {
        if (input.keyDown(KeyEvent.VK_LEFT)) {
            ballX -= 2;
        }
        if (input.keyDown(KeyEvent.VK_RIGHT)) {
            ballX += 2;
        }
        if (input.keyDown(KeyEvent.VK_UP)) {
            ballY -= 2;
        }
        if (input.keyDown(KeyEvent.VK_DOWN)) {
            ballY += 2;
        }
    }

    @Override
    public void render(Graphics g) {

        System.out.println(1);

        // 画一个红色的小球
        g.setColor(Color.RED);
        g.fillOval(ballX, ballY, 30, 30);
    }

}
