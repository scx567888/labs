package dev.scx.ffi.x.win32.helper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static dev.scx.ffi.x.win32.User32.USER32;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// 简化操作 Window
///
/// @author scx567888
/// @version 0.0.1
public final class WindowHelper {

    public static List<WindowInfo> FindAllWindow() throws Throwable {
        var list = new ArrayList<WindowInfo>();
        USER32.EnumWindows((hWnd, _) -> {
            //只要顶级窗口
            if (USER32.GetParent(hWnd).address() == 0) {
                var title = getWindowTitle(hWnd);
                var className = getWindowClassName(hWnd);
                var isVisible = USER32.IsWindowVisible(hWnd);
                var threadProcessId = USER32.GetWindowThreadProcessId(hWnd, Arena.global().allocate(JAVA_LONG));
                list.add(new WindowInfo(hWnd, className, title, isVisible, threadProcessId));
            }
            return true;
        }, 0);

        return list;
    }

    public static String getWindowTitle(MemorySegment hWnd) {
        int l = USER32.GetWindowTextLengthW(hWnd);
        var chars = new char[l];
        int l1 = USER32.GetWindowTextW(hWnd, chars, l + 1);
        return new String(chars, 0, l1);
    }

    public static String getWindowClassName(MemorySegment hWnd) {
        var chars = new char[512];
        int l1 = USER32.GetClassNameW(hWnd, chars, 512);
        return new String(chars, 0, l1);
    }

    public record WindowInfo(MemorySegment hWnd,
                             String className,
                             String title,
                             int isVisible,
                             MemorySegment threadProcessId) {

    }

}
