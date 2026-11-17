package dev.scx.physics;

import java.awt.*;

public class TestScene implements Scene{

    @Override
    public void update(Input input) {
        System.out.println(123);
    }

    @Override
    public void render(Graphics g) {

        System.out.println(1);

        // 画一个红色的小球
        g.setColor(Color.RED);
        g.fillOval(100, 100, 30, 30);
    }

}
