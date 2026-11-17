package dev.scx.physics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SceneRunner extends JPanel {

    /// 暂定 60 FPS
    public static final int FPS = 60;

    private final Scene scene;

    // 当前按下的按键
    private final Set<Integer> keysDown = new HashSet<>();

    public SceneRunner(Scene scene) {
        this.scene = scene;

        // 允许获得键盘焦点
        setFocusable(true);

        // 启用键盘事件和焦点事件
        enableEvents(
            AWTEvent.KEY_EVENT_MASK |
                AWTEvent.FOCUS_EVENT_MASK
        );

        var timer = new Timer(1000 / FPS, e -> {
            _update();
        });
        timer.start();
    }

    private void _update() {
        scene.update(new SceneRunnerInput(Collections.unmodifiableSet(keysDown)));
        repaint();
    }

    // 绘制回调
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        scene.render(g);
    }


    @Override
    protected void processKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();

        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> keysDown.add(key);
            case KeyEvent.KEY_RELEASED -> keysDown.remove(key);
        }

        super.processKeyEvent(e);
    }

    @Override
    protected void processFocusEvent(FocusEvent e) {
        if (e.getID() == FocusEvent.FOCUS_LOST) {
            keysDown.clear();
        }

        super.processFocusEvent(e);
    }


    static class SceneRunnerInput implements Input {

        private final Set<Integer> keyDowns;

        SceneRunnerInput(Set<Integer> keyDowns) {
            this.keyDowns = keyDowns;
        }

        @Override
        public boolean keyDown(int key) {
            return keyDowns.contains(key);
        }

    }

}
