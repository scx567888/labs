package dev.scx.physics;

import javax.swing.*;
import java.awt.*;

public class SceneRunner extends JPanel {

    /// 暂定 60 FPS
    public static final int FPS = 60;

    private final Scene scene;

    public SceneRunner(Scene scene) {
        this.scene = scene;
        var timer = new Timer(1000 / FPS, e -> {
            _update();
        });
        timer.start();
    }

    private void _update() {
        scene.update(new SceneRunnerInput());
        repaint();
    }

    // 绘制回调
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        scene.render(g);
    }

    static class SceneRunnerInput implements Input{

    }

}
