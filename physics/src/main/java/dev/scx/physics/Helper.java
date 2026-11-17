package dev.scx.physics;

import javax.swing.*;

public class Helper {

    public static void runScene(Scene scene) {
        SwingUtilities.invokeLater(() -> {
            // 创建窗口
            var jFrame = new JFrame();

            // 设置窗口大小
            jFrame.setSize(800, 600);

            // 设置关闭操作
            jFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            // 设置主内容面板
            jFrame.setContentPane(new SceneRunner(scene));

            // 显示窗口
            jFrame.setVisible(true);


        });
    }
}
