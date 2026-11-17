package dev.scx.physics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;

public class SceneRunner extends JPanel {

    /// 暂定 60 FPS
    public static final int FPS = 60;

    private final Scene scene;

    private final Timer updateTimer;

    // 当前按下的按键
    private final Set<Integer> keyDowns = new HashSet<>();

    // 当前按下的鼠标按钮
    private final Set<Integer> mouseDowns = new HashSet<>();

    // 鼠标位置
    private int mouseX = -1;
    private int mouseY = -1;

    public SceneRunner(Scene scene) {
        this.scene = scene;

        // 允许获得键盘焦点
        setFocusable(true);

        // 启用键盘事件和焦点事件
        enableEvents(
            AWTEvent.KEY_EVENT_MASK |
                AWTEvent.FOCUS_EVENT_MASK |
                AWTEvent.MOUSE_EVENT_MASK |
                AWTEvent.MOUSE_MOTION_EVENT_MASK
        );

        updateTimer = new Timer(1000 / FPS, e -> {
            _update();
        });


        // 这里通过轮询判断 是否已经可以安全调用 start
        new Timer(1000 / FPS, e -> {
            if (getWidth() <= 0 || getHeight() <= 0) {
                return;
            }

            // 启动条件满足，结束轮询。
            ((Timer) e.getSource()).stop();

            _start();
        }).start();
    }

    private void _start() {
        var context = createSceneContext();
        scene.start(context);
        updateTimer.start();
    }

    private void _update() {
        var context = createSceneContext();
        scene.update(context);
        repaint();
    }

    private SceneRunnerSceneContext createSceneContext() {
        var input = new SceneRunnerInput(
            Set.copyOf(keyDowns),
            Set.copyOf(mouseDowns),
            mouseX,
            mouseY
        );

        var viewport = new SceneRunnerViewport(getWidth(), getHeight());

        var _window = SwingUtilities.getWindowAncestor(this);

        var window = new SceneRunnerWindowInfo(
            _window.getX(),
            _window.getY(),
            _window.getWidth(),
            _window.getHeight()
        );

        return new SceneRunnerSceneContext(input, viewport, window);
    }

    // 绘制回调
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        scene.render(g);
    }

    /// 键盘事件
    @Override
    protected void processKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();

        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> keyDowns.add(key);
            case KeyEvent.KEY_RELEASED -> keyDowns.remove(key);
        }

        super.processKeyEvent(e);
    }

    /// 鼠标按钮事件
    @Override
    protected void processMouseEvent(MouseEvent e) {
        int button = e.getButton();

        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED -> mouseDowns.add(button);
            case MouseEvent.MOUSE_RELEASED -> mouseDowns.remove(button);
        }

        mouseX = e.getX();
        mouseY = e.getY();

        super.processMouseEvent(e);
    }

    /// 鼠标移动事件
    @Override
    protected void processMouseMotionEvent(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();

        super.processMouseMotionEvent(e);
    }

    /// 焦点事件
    @Override
    protected void processFocusEvent(FocusEvent e) {
        if (e.getID() == FocusEvent.FOCUS_LOST) {
            keyDowns.clear();
            mouseDowns.clear();
        }

        super.processFocusEvent(e);
    }


    static class SceneRunnerInput implements Input {

        private final Set<Integer> keyDowns;
        private final Set<Integer> mouseDowns;

        private final int mouseX;
        private final int mouseY;

        SceneRunnerInput(Set<Integer> keyDowns, Set<Integer> mouseDowns, int mouseX, int mouseY) {
            this.keyDowns = keyDowns;
            this.mouseDowns = mouseDowns;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
        }

        @Override
        public boolean keyDown(int key) {
            return keyDowns.contains(key);
        }

        @Override
        public boolean mouseDown(int button) {
            return mouseDowns.contains(button);
        }

        @Override
        public int mouseX() {
            return mouseX;
        }

        @Override
        public int mouseY() {
            return mouseY;
        }

    }


    public record SceneRunnerViewport(
        int width,
        int height
    ) implements Viewport {

    }

    public record SceneRunnerWindowInfo(
        int x,
        int y,
        int width,
        int height
    ) implements WindowInfo {

    }

    public record SceneRunnerSceneContext(
        Input input,
        Viewport viewport,
        WindowInfo window
    ) implements SceneContext {}

}
