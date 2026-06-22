package dev.scx.ffi.x.test;


import dev.scx.ffi.x.win32.WinUser;

import static dev.scx.ffi.x.win32.User32.USER32;

public class User32Test {

    public static void main(String[] args) {
        test1();
        test2();// 用鼠标画一个圆
    }

    public static void test1() {
        USER32.MessageBoxA(null, "测试中文内容", "😀😁😂测试标题", 0);
    }

    public static void test2() {

        // 获取当前鼠标位置（作为圆边的一个点）
        WinUser.POINT currentPos = new WinUser.POINT();
        USER32.GetCursorPos(currentPos);

        int radius = 200;
        int steps = 2000;

        // 计算圆心位置（假设当前点为圆右侧的点, 圆心在其左侧半径距离处）
        int centerX = currentPos.x - radius;
        int centerY = currentPos.y;

        // 画圆
        for (int i = 0; i < steps; i = i + 1) {
            double angle = 2 * Math.PI * i / steps;
            int x = centerX + (int) (radius * Math.cos(angle));
            int y = centerY + (int) (radius * Math.sin(angle));
            USER32.SetCursorPos(x, y);
            try {
                Thread.sleep(1);  // 等待1毫秒
            } catch (InterruptedException _) {

            }
        }

        // 最后确保回到起始点（由于浮点运算可能存在的误差, 这里显式设置）
        USER32.SetCursorPos(currentPos.x, currentPos.y);

    }

}
