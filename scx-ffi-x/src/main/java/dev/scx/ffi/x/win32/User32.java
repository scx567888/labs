package dev.scx.ffi.x.win32;

import dev.scx.ffi.ScxFFI;
import dev.scx.ffi.x.win32.WinUser.WNDENUMPROC;

import java.lang.foreign.MemorySegment;

import static dev.scx.ffi.x.win32.WinDef.*;

/// 提供一些 User32 标准的接口
///
/// @author scx567888
/// @version 0.0.1
public interface User32 {

    User32 USER32 = ScxFFI.createFFI( User32.class,"user32");

    // https://learn.microsoft.com/zh-cn/windows/win32/api/winuser/nf-winuser-messageboxa
    int MessageBoxA(MemorySegment hWnd, String lpText, String lpCaption, int uType);

    // https://learn.microsoft.com/zh-cn/windows/win32/api/winuser/nf-winuser-iswindowvisible
    int IsWindowVisible(MemorySegment hWnd);

    MemorySegment GetParent(MemorySegment hWnd);

    int GetWindowTextLengthW(MemorySegment hWnd);

    int GetClassNameW(MemorySegment hWnd, char[] lpClassName, int nMaxCount);

    int GetWindowTextW(MemorySegment hWnd, char[] lpString, int nMaxCount);

    MemorySegment GetWindowThreadProcessId(MemorySegment hWnd, MemorySegment lpdwProcessId);

    int PostMessageW(MemorySegment hWnd, int Msg, MemorySegment wParam, MemorySegment lParam);

    int SendMessageW(MemorySegment hWnd, int Msg, MemorySegment wParam, MemorySegment lParam);

    int EnumWindows(WNDENUMPROC lpEnumFunc, long lParam);

    MemorySegment FindWindowA(String lpClassName, String lpWindowName);

    int CloseWindow(MemorySegment hWnd);

    int GetWindowRect(MemorySegment hWnd, RECT lpRect);

    int SetCursorPos(int x, int y);

    int GetCursorPos(WinUser.POINT lpPoint);

}
