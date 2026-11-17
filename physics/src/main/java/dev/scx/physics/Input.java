package dev.scx.physics;

public interface Input {

    /// 暂用 [java.awt.event.KeyEvent]
    boolean keyDown(int key);

    /// 暂用 [java.awt.event.MouseEvent]
    boolean mouseDown(int button);

    int mouseX();

    int mouseY();

}
